package co.topl.nodeView.state

import java.io.File

import co.topl.attestation.Address
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.transaction._
import co.topl.nodeView.state.MinimalState.VersionTag
import co.topl.modifier.box._
import co.topl.modifier.box.serialization.BoxSerializer
import co.topl.settings.AppSettings
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.{Logging, NetworkType}
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.util.encode.Base58

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/** BifrostState is a data structure which deterministically defines whether an arbitrary transaction is valid and so
  * applicable to it or not. Also has methods to get a closed box, to apply a persistent modifier, and to roll back
  * to a previous version.
  *
  * @param storage singleton Iodb storage instance
  * @param version blockId used to identify each block. Also used for rollback
  *                //@param timestamp timestamp of the block that results in this state
  */
case class State(
  override val version:      VersionTag,
  protected val storage:     LSMStore,
  private[state] val tbrOpt: Option[TokenBoxRegistry] = None,
  private[state] val pbrOpt: Option[ProgramBoxRegistry] = None,
  nodeKeys:                  Option[Set[Address]] = None
)(implicit networkPrefix:    NetworkPrefix)
    extends MinimalState[Block, State]
    with StoreInterface
    with Logging {

  override type NVCT = State

  lazy val hasTBR: Boolean = tbrOpt.isDefined
  lazy val hasPBR: Boolean = pbrOpt.isDefined

  override def closeStorage(): Unit = {
    log.info("Attempting to close state storage")
    super.closeStorage()

    log.info("Attempting to close token box registry storage")
    tbrOpt.foreach(_.closeStorage())

    log.info("Attempting to close program box registry storage")
    pbrOpt.foreach(_.closeStorage())
  }

  /** Accessor method to retrieve box data from the state storage
    *
    * @param id unique identifier where the box data is stored
    * @return
    */
  override def getBox(id: BoxId): Option[Box[_]] =
    getFromStorage(id.hashBytes)
      .map(BoxSerializer.parseBytes)
      .flatMap(_.toOption)

  /** Accessor method to retrieve program box data from state by abstracting the
    * registry lookup and subsequent state access
    *
    * @param key the program id of the program box to retrieve
    * @tparam PBX the type of box that you are expecting to get back (StateBox, ExecBox, etc.)
    * @return a program box of the specified type if found at the given program is
    */
  override def getProgramBox[PBX <: ProgramBox: ClassTag](key: ProgramId): Option[PBX] =
    pbrOpt.flatMap(_.getBox(key, getReader) match {
      case Some(box: PBX) => Some(box)
      case _              => None
    })

  /** Accessor method to retrieve a set of token boxes from state that are owned by a
    * certain public key. This works by abstracting the registry lookup and subsequent state access
    *
    * @param key the public key to find boxes for
    * @return a sequence of token boxes held by the public key
    */
  override def getTokenBoxes(key: Address): Option[Seq[TokenBox[TokenValueHolder]]] =
    tbrOpt.flatMap(_.getBox(key, getReader))

  /** Lookup a sequence of boxIds from the appropriate registry.
    * These boxIds can then be used in `getBox` to retrieve the box data.
    *
    * @param key storage key used to identify value(s) in registry
    * @return a sequence of boxes stored beneath the specified key
    */
  def registryLookup[K](key: K): Option[Seq[BoxId]] =
    key match {
      case k: TokenBoxRegistry.K if tbrOpt.isDefined   => tbrOpt.get.lookup(k)
      case k: ProgramBoxRegistry.K if pbrOpt.isDefined => pbrOpt.get.lookup(k)
      case _ if pbrOpt.isEmpty | tbrOpt.isEmpty        => None
    }

  /** Revert version of state to a specific version
    *
    * @param version tag marking a specific state within the store
    * @return an instance of State at the version given
    */
  override def rollbackTo(version: VersionTag): Try[NVCT] = Try {

    // throwing error here since we should stop attempting updates if any part fails
    val updatedTBR = if (tbrOpt.isDefined) {
      tbrOpt.get.rollbackTo(version) match {
        case Success(updates) => Some(updates)
        case Failure(ex)      => throw new Error(s"Failed to rollback TBR with error\n$ex")
      }
    } else {
      None
    }

    val updatedPBR = if (pbrOpt.isDefined) {
      pbrOpt.get.rollbackTo(version) match {
        case Success(updates) => Some(updates)
        case Failure(ex)      => throw new Error(s"Failed to rollback PBR with error\n$ex")
      }
    } else {
      None
    }

    if (storage.lastVersionID.exists(_.data sameElements version.bytes)) {
      this
    } else {
      log.debug(s"Rollback State to $version from version ${this.version.toString}")
      storage.rollback(ByteArrayWrapper(version.bytes))

      State(version, storage, updatedTBR, updatedPBR, nodeKeys)
    }
  }

  /** Public method used to update an instance of state with a new set of transactions
    *
    * @param block block to be applied to state
    * @return a new instance of state with the transaction within mod applied
    */
  override def applyModifier(block: Block): Try[State] = {
    // extract the state changes to be made
    // using option for TBR and PBR since we can skip if the registries aren't present
    val stateChanges = StateChanges(block)
    val tokenChanges = if (tbrOpt.isDefined) TokenRegistryChanges(block).toOption else None
    val programChanges = if (pbrOpt.isDefined) ProgramRegistryChanges(block).toOption else None

    // if the registries update successfully, attempt to update the utxo storage
    stateChanges match {
      case Success(sc) => applyChanges(block.id, sc, tokenChanges, programChanges)
      case Failure(_)  => throw new Error(s"Failed to calculate state changes for block: ${block.id}")
    }
  }

  /** Apply a series of changes to the internal stores of the state objects
    *
    * @param newVersion version id of this update
    * @param stateChanges changes to be made to state storage
    * @param tokenChanges changes to be made to the token box registry
    * @param programChanges changes to be made to the program box registry
    * @return an updated version of state
    */
  private[state] def applyChanges(
    newVersion:     VersionTag,
    stateChanges:   StateChanges,
    tokenChanges:   Option[TokenRegistryChanges] = None,
    programChanges: Option[ProgramRegistryChanges] = None
  ): Try[NVCT] =
    Try {

      //Filtering boxes pertaining to public keys specified in settings file
      val boxesToAdd = (nodeKeys match {
        case Some(keys) => stateChanges.toAppend.filter(b => keys.contains(Address(b.evidence)))
        case None       => stateChanges.toAppend
      }).map(b => ByteArrayWrapper(b.id.hashBytes) -> ByteArrayWrapper(b.bytes))

      val boxIdsToRemove = (nodeKeys match {
        case Some(keys) =>
          stateChanges.boxIdsToRemove
            .flatMap(getBox)
            .filter(b => keys.contains(Address(b.evidence)))
            .map(b => b.id)

        case None => stateChanges.boxIdsToRemove
      }).map(b => ByteArrayWrapper(b.hashBytes))

      // enforce that the input id's must not match any of the output id's (added emptiness checks for testing)
      require(
        !boxesToAdd.map(_._1).forall(boxIdsToRemove.contains) || (boxesToAdd.isEmpty || boxIdsToRemove.isEmpty),
        s"Attempted application of invalid state"
      )

      log.debug(
        s"Attempting update to State from version ${version.toString} to version $newVersion. " +
        s"Removing boxes with ids ${boxIdsToRemove.map(b => Base58.encode(b.data))}. " +
        s"Adding boxes ${boxesToAdd.map(b => Base58.encode(b._1.data))}."
      )

      if (storage.lastVersionID.isDefined) {
        stateChanges.boxIdsToRemove
          .foreach { id =>
            require(
              getBox(id).isDefined,
              s"Box id: $id not found in state version: " +
              s"${Base58.encode(storage.lastVersionID.get.data)}. Aborting state update"
            )
          }
      }

      // throwing error here since we should stop attempting updates if any part fails
      val updatedTBR = tokenChanges match {
        case Some(tc) =>
          tbrOpt.get.update(newVersion, tc.toRemove, tc.toAppend) match {
            case Success(updates) => Some(updates)
            case Failure(ex)      => throw new Error(s"Failed to update TBR with error\n$ex")
          }
        case _ => None
      }

      val updatedPBR = programChanges match {
        case Some(pc) =>
          pbrOpt.get.update(newVersion, pc.toRemove, pc.toUpdate) match {
            case Success(updates) => Some(updates)
            case Failure(ex)      => throw new Error(s"Failed to update PBR with error\n$ex")
          }
        case _ => None
      }

      storage.update(ByteArrayWrapper(newVersion.bytes), boxIdsToRemove, boxesToAdd)

      // create updated instance of state
      val newState = State(newVersion, storage, updatedTBR, updatedPBR, nodeKeys)

      // enforce that a new valid state must have emptied all boxes to remove
      // boxIdsToRemove.foreach(box => require(newState.getBox(box).isEmpty, s"Box $box is still in state"))

      newState
    } match {
      case Success(newState) =>
        log.debug(s"${Console.GREEN} Update State to version: $newVersion ${Console.RESET}")
        Success(newState)

      case Failure(ex) =>
        log.debug(s"${Console.RED} Failed to update State to version: $newVersion. ${Console.RESET} $ex")
        Failure(ex)
    }

  /** @param transaction
    * @return
    */
  def semanticValidate(transaction: Transaction.TX)(implicit networkPrefix: NetworkPrefix): Try[Unit] =
    transaction.semanticValidate(getReader)
}

object State extends Logging {

  /** @param settings
    * @param initialBlocks
    * @param networkPrefix
    * @return
    */
  def genesisState(settings: AppSettings, initialBlocks: Seq[Block])(implicit networkPrefix: NetworkPrefix): State =
    initialBlocks
      .foldLeft(readOrGenerate(settings)) { (state, mod) =>
        state.applyModifier(mod).get
      }

  def exists(settings: AppSettings): Boolean = stateFile(settings).exists()

  /** Construct and returns the directory where state data will be stored
    * @param settings the configuration file for the node
    * @return a file where data is stored
    */
  def stateFile(settings: AppSettings): File = {
    val dataDir = settings.application.dataDir.ensuring(_.isDefined, "A data directory must be specified").get
    new File(s"$dataDir/state")
  }

  /** @param settings
    * @param networkPrefix
    * @return
    */
  def readOrGenerate(settings: AppSettings)(implicit networkPrefix: NetworkPrefix): State = {
    val sFile = stateFile(settings)
    sFile.mkdirs()
    val storage = new LSMStore(sFile, keySize = BoxId.size)

    val version: VersionTag =
      storage.lastVersionID
        .fold(Option(ModifierId.empty))(bw => ModifierId.parseBytes(bw.data).toOption)
        .getOrElse(throw new Error("Unable to define state version during initialization"))

    // node keys are a set of keys that this node will restrict its state to update
    val nodeKeys: Option[Set[Address]] = settings.application.nodeKeys match {
      case None                       => None
      case Some(keys) if keys.isEmpty => None
      case Some(keys)                 => Some(keys.map(Address(networkPrefix)(_)))
    }

    if (nodeKeys.isDefined) log.info(s"Initializing state to watch for public keys: $nodeKeys")
    else log.info("Initializing state to watch for all public keys")

    val pbr = ProgramBoxRegistry.readOrGenerate(settings)
    val tbr = TokenBoxRegistry.readOrGenerate(settings, nodeKeys)

    State(version, storage, tbr, pbr, nodeKeys)
  }
}
