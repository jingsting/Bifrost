package example

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import _root_.requests.{Requests, RequestsManager}
import akka.util.{ByteString, Timeout}
import crypto.AddressEncoder.NetworkPrefix
import crypto.{Address, KeyfileCurve25519, NewBox, PrivateKeyCurve25519, PublicKeyPropositionCurve25519}
import io.circe.{Json, parser}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import keymanager.Keys
import settings.NetworkType
import wallet.WalletManager
import wallet.WalletManager._

import scala.reflect.io.Path
import scala.util.{Failure, Success, Try}
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.collection.mutable.{Map => MMap}
import scala.concurrent.duration._

/**
  * Must be running bifrost with --local and --seed test
  * ex: "run --local --seed test -f"
  */
class RequestSpec extends AsyncFlatSpec
  with Matchers
  with GjallarhornGenerators {

  implicit val actorSystem: ActorSystem = ActorSystem("requestTest", requestConfig)
  implicit val context: ExecutionContextExecutor = actorSystem.dispatcher
  implicit val timeout: Timeout = 30.seconds
  implicit val networkPrefix: NetworkPrefix = 48.toByte //local network

  val bifrostActor: ActorRef = Await.result(actorSystem.actorSelection(
    s"akka.tcp://${settings.application.chainProvider}/user/walletConnectionHandler").resolveOne(), 10.seconds)

  val requestsManagerRef: ActorRef = actorSystem.actorOf(
    Props(new RequestsManager(bifrostActor)), name = "RequestsManager")
  val requests = new Requests(requestSettings.application, requestsManagerRef)

  val keyFileDir = "keyfiles/requestTestKeys"
  val path: Path = Path(keyFileDir)
  Try(path.deleteRecursively())
  Try(path.createDirectory())
  val password = "pass"
  val genesisPubKey: Address = Address("86th32F6fUxGZi8KLShhQkYxHs3hyDFVh64HSWufQieJSVYXtWAN")
  val keyManager: Keys[PrivateKeyCurve25519, KeyfileCurve25519] = Keys(keyFileDir, KeyfileCurve25519)
  //keyManager.unlockKeyFile(Base58.encode(sk1.publicKeyBytes), password)

  val privateKeys: Set[PrivateKeyCurve25519] = keyManager.generateNewKeyPairs(2, Some("test")) match {
    case Success(secrets) => secrets
    case Failure(ex) => throw new Error (s"Unable to generate new keys: $ex")
  }

  val addresses: Set[Address] = privateKeys.map(sk => sk.publicImage.address)

  val (pk1, sk1) = (addresses.head, privateKeys.head)
  val (pk2, sk2) = (addresses.tail.head, privateKeys.tail.head)

  val pk3: Address = keyManager.generateKeyFile("password3") match {
    case Success(address) => address
    case Failure(ex) => throw ex
  }

  val publicKeys: Set[Address] = Set(pk1, pk2, pk3, genesisPubKey)
  val walletManagerRef: ActorRef = actorSystem.actorOf(
    Props(new WalletManager(bifrostActor)), name = "WalletManager")

  val amount = 10
  var transaction: Json = Json.Null
  var signedTransaction: Json = Json.Null
  var newBoxIds: Set[String] = Set()

  def parseForBoxId(json: Json): Set[String] = {
    val result = (json \\ "result").head
    val newBxs = (result \\ "newBoxes").head.toString()
    parser.decode[List[NewBox]](newBxs) match {
      case Right(newBoxes) =>
        newBoxes.foreach(newBox => {
          newBoxIds += newBox.id
        })
        newBoxIds
      case Left(e) => sys.error(s"could not parse: $newBxs")
    }
  }

  it should "connect to bifrost actor when the gjallarhorn app starts" in {
    walletManagerRef ! GjallarhornStarted
    val bifrostResponse = Await.result((walletManagerRef ? GetNetwork).mapTo[String], 10.seconds)
    val networkName = bifrostResponse.split("Bifrost is running on").tail.head.replaceAll("\\s", "")
    val networkPre: NetworkPrefix = NetworkType.fromString(networkName) match {
      case Some(network) => network.netPrefix
      case None => throw new Error(s"The network name: $networkName was not a valid network type!")
    }
    walletManagerRef ! YourKeys(publicKeys)
    assert(networkPre == networkPrefix)
  }


  it should "receive a successful response from Bifrost upon creating asset" in {
    val createAssetRequest: ByteString = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "1",
         |   "method": "topl_rawAssetTransfer",
         |   "params": [{
         |     "propositionType": "PublicKeyCurve25519",
         |     "sender": ["$pk1"],
         |     "recipient": [["$pk1", $amount]],
         |     "changeAddress": "$pk1",
         |     "issuer": "$pk1",
         |     "assetCode": "test",
         |     "minting": true,
         |     "fee": 1,
         |     "data": ""
         |   }]
         |}
       """.stripMargin)
    val tx = requests.sendRequest(createAssetRequest)
    assert(tx.isInstanceOf[Json])
    (tx \\ "error").isEmpty shouldBe true
    (tx \\ "result").head.asObject.isDefined shouldBe true
  }

  it should "receive a successful response from Bifrost upon transfering arbit" in {
    val transferArbitsRequest: ByteString = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "1",
         |   "method": "topl_rawArbitTransfer",
         |   "params": [{
         |     "propositionType": "PublicKeyCurve25519",
         |     "recipient": [["$pk2", $amount]],
         |     "sender": ["$pk1"],
         |     "changeAddress": "$pk1",
         |     "fee": 1,
         |     "data": ""
         |   }]
         |}
       """.stripMargin)
    transaction = requests.sendRequest(transferArbitsRequest)
    newBoxIds = parseForBoxId(transaction)
    assert(transaction.isInstanceOf[Json])
    (transaction \\ "error").isEmpty shouldBe true
    (transaction \\ "result").head.asObject.isDefined shouldBe true
  }


  it should "receive successful JSON response from sign transaction" in {
    val issuer: List[String] = List(publicKeys.head.toString)
    val response = requests.signTx(transaction, keyManager, issuer)
    (response \\ "error").isEmpty shouldBe true
    (response \\ "result").head.asObject.isDefined shouldBe true
    signedTransaction = (response \\ "result").head
    assert((signedTransaction \\ "signatures").head.asObject.isDefined)
    val sigs: Map[PublicKeyPropositionCurve25519, Json] =
      (signedTransaction \\ "signatures").head.as[Map[PublicKeyPropositionCurve25519, Json]] match {
      case Left(error) => throw (error)
      case Right(value) => value
    }
    val pubKeys = sigs.keySet.map(pubKey => pubKey.address)
    issuer.foreach(key => assert(pubKeys.contains(Address(key))))
    (signedTransaction \\ "tx").nonEmpty shouldBe true
  }

  it should "receive successful JSON response from broadcast transaction" in {
    val response = requests.broadcastTx(signedTransaction)
    assert(response.isInstanceOf[Json])
    (response \\ "error").isEmpty shouldBe true
    (response \\ "result").head.asObject.isDefined shouldBe true
  }

  var balanceResponse: Json = Json.Null

  it should "receive a successful and correct response from Bifrost upon requesting balances" in {
    Thread.sleep(10000)
    balanceResponse = requests.getBalances(publicKeys.map(addr => addr.toString))
    assert(balanceResponse.isInstanceOf[Json])
    (balanceResponse \\ "error").isEmpty shouldBe true
    val result: Json = (balanceResponse \\ "result").head
    result.asObject.isDefined shouldBe true
    (result \\ pk1.toString).nonEmpty shouldBe true
    val contains = newBoxIds.map(boxId => result.toString().contains(boxId))
    contains.contains(false) shouldBe false
  }

  //Make sure you re-run bifrost for this to pass.
  it should "update boxes correctly with balance response" in {
    val walletBoxes: MMap[String, MMap[String, Json]] =
      Await.result((walletManagerRef ? UpdateWallet((balanceResponse \\ "result").head))
      .mapTo[MMap[String, MMap[String, Json]]], 10.seconds)
    val pubKeyEmptyBoxes: Option[MMap[String, Json]] = walletBoxes.get(pk3.toString)
    pubKeyEmptyBoxes match {
      case Some(map) => assert(map.keySet.isEmpty)
      case None => sys.error(s"no mapping for given public key: ${pk3.toString}}")
    }

    val pk1Boxes: Option[MMap[String, Json]] = walletBoxes.get(pk1.toString)
    pk1Boxes match {
      case Some(map) => assert (map.size === 2)
      case None => sys.error(s"no mapping for given public key: ${pk1.toString}")
    }
    val pk2Boxes: Option[MMap[String, Json]] = walletBoxes.get(pk2.toString)
    pk2Boxes match {
      case Some(map) => assert (map.size === 3)
      case None => sys.error(s"no mapping for given public key: ${pk2.toString}")
    }
  }

  it should "receive a block from bifrost after creating a transaction" in {
    val newBlock: Option[String] = Await.result((walletManagerRef ? GetNewBlock).mapTo[Option[String]], 10.seconds)
    newBlock match {
      case Some(block) => assert(block.contains("timestamp") && block.contains("signature") && block.contains("txId")
        && block.contains("newBoxes"))
      case None => sys.error("no new blocks")
    }
  }


  it should "send msg to bifrost actor when the gjallarhorn app stops" in {
    val bifrostResponse: String = Await.result((walletManagerRef ? GjallarhornStopped).mapTo[String], 100.seconds)
    assert(bifrostResponse.contains("The remote wallet Actor[akka.tcp://requestTest@127.0.0.1") &&
      bifrostResponse.contains("has been removed from the WalletConnectionHandler in Bifrost"))
  }

  /*it should "update wallet correctly after receiving new block" in {
    val block: ByteString = ByteString(
      s"""
         |    [
         |      {
         |        "txType" : "Coinbase",
         |        "txHash" : "HK5CxRpT1xXBbLeQRnxzBfMZkTr2czwbEcCchRofsx9z",
         |        "timestamp" : 1603750438238,
         |        "signatures" : {
         |          "4YCxsBZujUFEfEaRWhhURgzvWEzN8BWbbho1qEovmhunN7c9fQ" : "Signature25519(5h4GoyqCYC8qhvzDHoheD442JSu7YaE5bNFFemfVhfwGLZtvUYGgXyZ35jYyhG2YtUAW6pnwpkDVdgq2GpAux3xS)"
         |        },
         |        "newBoxes" : [
         |          {
         |            "nonce": "-9110370178208068175",
         |            "id": "GGDsEQdd5cnbgjKkac9HLpp2joGo6bWgmS2KvhJgd8b8",
         |            "type": "Arbit",
         |            "proposition": "${pk2.toString}",
         |            "value": "1000000"
         |          },
         |          {
         |            "nonce": "-8269943573030898832",
         |            "id": "97gkUUwPQWGKU1LMf7cZE4PGdbUezWYLcbcuiRRryGeE",
         |            "type": "Arbit",
         |            "proposition": "4EoSC4YmTm7zoPt5HDJU4aa73Vn2LPrmUszvggAPM5Ff3R1DVt",
         |            "value": "1000000"
         |          }
         |        ],
         |        "to" : [
         |          [
         |            "4Nb1ewkxoT8GJAZkCLaetQAWgVdfcu58v5Uka8iag3nsXakiVj",
         |            0
         |          ]
         |        ],
         |        "parentId" : "HVa5fBayzLEDStb9Hwthe9HDJWAtakGW3o11s9z2cRo4",
         |        "fee" : 0
         |      },
         |      {
         |        "txType": "PolyTransfer",
         |        "txHash": "G1KX8RPVBBmHWuuZ7ihNkQLXVJa8AMr4DxafAJHUUCuy",
         |        "timestamp": 0,
         |        "signatures": {
         |          "2xdTv8awN1BjgYEw8W1BVXVtiEwG2b29U8KoZQqJrDuEqSQ9e4": "Signature25519(2AXDGYSE4f2sz7tvMMzyHvUfcoJmxudvdhBcmiUSo6ijwfYmfZYsKRxboQMPh3R4kUhXRVdtSXFXMheka4Rc4P2)"
         |        },
         |        "newBoxes": [
         |          {
         |             "nonce": "-5988475187915922381",
         |             "id": "GgNqzkSywewv99vCrb99UakEw1Myn4mqYXo3N4a6PWVW",
         |             "type": "Poly",
         |             "proposition": "3X4AW3Swr1iM1syu2g4Xi4L4eTSJFKxGsZPgVctUYg4ga8MZpD",
         |             "value": "1000000"
         |          },
         |          {
         |             "nonce": "965750754031143229",
         |             "id": "5UGTHuvG7kJVqp9Sw55A1C6wVEtgeQKn12njLG1bbUTK",
         |             "type": "Poly",
         |             "proposition": "4EoSC4YmTm7zoPt5HDJU4aa73Vn2LPrmUszvggAPM5Ff3R1DVt",
         |             "value": "1000000"
         |          },
         |          {
         |             "nonce": "-59884751870915922381",
         |             "id": "GgNqzkSywewv10vCrb99UakEw1Myn5mqYXo3N4a6PWVW",
         |             "type": "Poly",
         |             "proposition": "${pk2.toString}",
         |             "value": "1000000"
         |          }
         |        ],
         |        "to" : [
         |          {
         |            "proposition" : "6sYyiTguyQ455w2dGEaNbrwkAWAEYV1Zk6FtZMknWDKQ",
         |            "value" : 0
         |          }
         |        ],
         |        "boxesToRemove": [
         |                        "2fvgQ6xAJbMxtsGv73veyN3sHnwKUh2Lda3b9CyNxriv"
         |        ],
         |        "fee" : 0
         |      }
         |    ]
       """.stripMargin)
    parser.parse(block.utf8String) match {
      case Right(blockJson) =>
        walletManagerRef ! s"new block added: $blockJson"
        Thread.sleep(1000)
        val walletBoxes: MMap[String, MMap[String, Json]] = Await.result((walletManagerRef ? GetWallet)
          .mapTo[MMap[String, MMap[String, Json]]], 10.seconds)
        val pk1Boxes: Option[MMap[String, Json]] = walletBoxes.get(pk2.toString)
        pk1Boxes match {
          case Some(map) =>
            assert(map.size == 2)
            assert(map.contains("GGDsEQdd5cnbgjKkac9HLpp2joGo6bWgmS2KvhJgd8b8"))
            map.get("GgNqzkSywewv10vCrb99UakEw1Myn5mqYXo3N4a6PWVW") match {
              case Some(json) => assert((json \\ "type").head.toString() == "\"Poly\"")
              case None => sys.error("poly box was not found!")
            }
          case None => sys.error(s"no mapping for given public key: ${pk2.toString}")
        }
      case Left(e) => sys.error(s"Could not parse json $e")
    }
  }*/

}
