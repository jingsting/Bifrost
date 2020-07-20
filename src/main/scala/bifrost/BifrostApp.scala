package bifrost

import java.lang.management.ManagementFactory
import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import bifrost.consensus.ForgerRef
import bifrost.crypto.PrivateKey25519
import bifrost.history.History
import bifrost.http.HttpService
import bifrost.http.api.ApiRoute
import bifrost.http.api.routes._
import bifrost.mempool.MemPool
import bifrost.modifier.block.Block
import bifrost.modifier.box.Box
import bifrost.modifier.box.proposition.ProofOfKnowledgeProposition
import bifrost.modifier.transaction.bifrostTransaction.Transaction
import bifrost.network._
import bifrost.network.message._
import bifrost.nodeView.{NodeViewHolder, NodeViewHolderRef, NodeViewModifier}
import bifrost.settings.{AppSettings, BifrostContext, NetworkType, StartupOpts}
import bifrost.utils.{Logging, NetworkTimeProvider}
import com.sun.management.{HotSpotDiagnosticMXBean, VMOption}
import com.typesafe.config.{Config, ConfigFactory}
import kamon.Kamon

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

class BifrostApp(startupOpts: StartupOpts) extends Logging with Runnable {

  import bifrost.network.NetworkController.ReceivableMessages.ShutdownNetwork

  type P = ProofOfKnowledgeProposition[PrivateKey25519]
  type BX = Box
  type TX = Transaction
  type PMOD = Block
  type NVHT = NodeViewHolder

  private val settings: AppSettings = AppSettings.read(startupOpts)
  log.debug(s"Starting application with settings \n$settings")

  private val conf: Config = ConfigFactory.load("application")
  private val ApplicationNameLimit: Int = conf.getInt("app.applicationNameLimit")

  protected implicit lazy val actorSystem: ActorSystem = ActorSystem(settings.network.agentName)
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  protected val features: Seq[peer.PeerFeature] = Seq()
  protected val additionalMessageSpecs: Seq[MessageSpec[_]] = Seq(BifrostSyncInfoMessageSpec)

  private val timeProvider = new NetworkTimeProvider(settings.ntp)

  // check for gateway device and setup port forwarding
  private val upnpGateway: Option[upnp.Gateway] = if (settings.network.upnpEnabled) upnp.getValidGateway(settings.network) else None
  // TODO use random port on gateway instead settings.network.bindAddress.getPort
  upnpGateway.foreach(_.addPort(settings.network.bindAddress.getPort))

  // save your address for sending to others peers
  lazy val externalSocketAddress: Option[InetSocketAddress] = {
    settings.network.declaredAddress orElse {
      // TODO use the random port on gateway instead settings.bindAddress.getPort
      upnpGateway.map(u => new InetSocketAddress(u.externalAddress, settings.network.bindAddress.getPort))
    }
  }

  private lazy val basicSpecs = {
    val invSpec = new InvSpec(settings.network.maxInvObjects)
    val requestModifierSpec = new RequestModifierSpec(settings.network.maxInvObjects)
    val modifiersSpec = new ModifiersSpec(settings.network.maxPacketSize)
    val featureSerializers: peer.PeerFeature.Serializers = features.map(f => f.featureId -> f.serializer).toMap
    Seq(
      GetPeersSpec,
      new PeersSpec(featureSerializers, settings.network.maxPeerSpecObjects),
      invSpec,
      requestModifierSpec,
      modifiersSpec
    )
  }

  private val bifrostContext: BifrostContext = BifrostContext(
    messageSpecs = basicSpecs ++ additionalMessageSpecs,
    features = features,
    upnpGateway = upnpGateway,
    timeProvider = timeProvider,
    externalNodeAddress = externalSocketAddress
  )

  // Create Bifrost singleton actors
  private val peerManagerRef: ActorRef = peer.PeerManagerRef("peerManager", settings, bifrostContext)

  private val networkControllerRef: ActorRef = NetworkControllerRef("networkController", settings.network, peerManagerRef, bifrostContext)

  private val nodeViewHolderRef: ActorRef = NodeViewHolderRef("nodeViewHolder", settings, timeProvider)

  private val forgerRef: ActorRef = ForgerRef("forger", settings, nodeViewHolderRef)

  private val nodeViewSynchronizer: ActorRef =
    NodeViewSynchronizerRef[Transaction, BifrostSyncInfo, BifrostSyncInfoMessageSpec.type, Block, History, MemPool](
      "nodeViewSynchronizer", networkControllerRef, nodeViewHolderRef,
      BifrostSyncInfoMessageSpec, settings.network, timeProvider, NodeViewModifier.modifierSerializers)

  // Register controllers for all API routes
  private val apiRoutes: Seq[ApiRoute] = Seq(
    DebugApiRoute(settings, nodeViewHolderRef),
    WalletApiRoute(settings, nodeViewHolderRef),
    ProgramApiRoute(settings, nodeViewHolderRef, networkControllerRef),
    AssetApiRoute(settings, nodeViewHolderRef),
    UtilsApiRoute(settings),
    NodeViewApiRoute(settings, nodeViewHolderRef)
  )

  private val httpService = HttpService(apiRoutes)

  // Am I running on a JDK that supports JVMCI?
  val vm_version: String = System.getProperty("java.vm.version")
  System.out.printf("java.vm.version = %s%n", vm_version)

  // Is JVMCI enabled?
  val bean: HotSpotDiagnosticMXBean = ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean])
  val enableJVMCI: VMOption = bean.getVMOption("EnableJVMCI")
  System.out.println(enableJVMCI)

  // Is the system using the JVMCI compiler for normal compilations?
  val useJVMCICompiler: VMOption = bean.getVMOption("UseJVMCICompiler")
  System.out.println(useJVMCICompiler)

  // What compiler is selected?
  val compiler: String = System.getProperty("jvmci.Compiler")
  System.out.printf("jvmci.Compiler = %s%n", compiler)

  def run(): Unit = {
    require(settings.network.agentName.length <= ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at 0.0.0.0:${settings.rpcPort}")

    implicit val materializer: ActorMaterializer = ActorMaterializer()
    // trigger the networking binding for the HTTP server and the protocol messenger
    networkControllerRef ! "Bind"
    Http().bindAndHandle(httpService.compositeRoute, "0.0.0.0", settings.rpcPort)

    /* on unexpected shutdown */
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        log.error("Unexpected shutdown")
        stopAll()
      }
    })
  }

  def stopAll(): Unit = synchronized {
    log.info("Stopping network services")
    if (settings.network.upnpEnabled) upnpGateway.foreach(_.deletePort(settings.network.bindAddress.getPort))
    networkControllerRef ! ShutdownNetwork

    log.info("Stopping actors (incl. block generator)")

    actorSystem.terminate().onComplete { _ =>
      log.info("Exiting from the app...")
      System.exit(0)
    }
  }
}

object BifrostApp extends Logging {
  private val conf: Config = ConfigFactory.load("application")
  if (conf.getBoolean("kamon.enable")) Kamon.init()

  import com.joefkelley.argyle._

  val argParser: Arg[StartupOpts] = (
    optional[String]("--config", "-c") and
      optionalOneOf[NetworkType](NetworkType.all.map(x => s"--${x.verboseName}" -> x): _*)
    ).to[StartupOpts]

  def main(args: Array[String]): Unit =
    argParser.parse(args) match {
      case Success(argsParsed) => new BifrostApp(argsParsed).run()
      case Failure(e) => throw e
    }

  def forceStopApplication(code: Int = 1): Nothing = sys.exit(code)

  def shutdown(system: ActorSystem, actors: Seq[ActorRef]): Unit = {
    log.warn("Terminating Actors")
    actors.foreach { a => a ! PoisonPill }
    log.warn("Terminating ActorSystem")
    val termination = system.terminate()
    Await.result(termination, 60.seconds)
    log.warn("Application has been terminated.")
  }
}