package example

import crypto.{PrivateKey25519, PublicKey25519Proposition}
import keymanager.{KeyFile, Keys}
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.crypto.hash.{Blake2b256, Digest32}

import scala.reflect.io.Path
import scala.util.{Failure, Success, Try}

@DoNotDiscover
class KeysSpec extends AsyncFlatSpec with Matchers {
  val randomBytes1: Digest32 = Blake2b256(java.util.UUID.randomUUID.toString)
  val randomBytes2: Digest32 = Blake2b256(java.util.UUID.randomUUID.toString)

  // Three keypairs for full test range, use library keygen method
  val (sk1, pk1) = PrivateKey25519.generateKeys(randomBytes1)
  val (sk2, pk2) =  PrivateKey25519.generateKeys(Blake2b256("sameEntropic")) //Hash of this string was taken as input instead of entropy
  val (sk3, pk3) =  PrivateKey25519.generateKeys(randomBytes1)

  //Filepath to write keypairs
  val keyFileDir = "keyfiles/keyManagerTest"
  val path: Path = Path(keyFileDir)
  Try(path.deleteRecursively())
  Try(path.createDirectory())
  val password = "password"

  val seedString: String = java.util.UUID.randomUUID.toString
  val seed1: Digest32 = Blake2b256(seedString)


  val keyManager = Keys(keyFileDir)
  var pubKeys: Set[PublicKey25519Proposition] = Set()
  var privKeys: Set[PrivateKey25519] = Set()
  var keyFiles: Set[KeyFile] = Set()

  //Hashmap where seeds map to public/private keypair
  var (priv, pub) = PrivateKey25519.generateKeys(seed1)
  if (!keyManager.publicKeys.contains(pub)) {
    keyManager.generateKeyFile(password)
    keyFiles += KeyFile.readFile(Keys.getListOfFiles(keyManager.defaultKeyDir).head.getPath)
  }
  println("first key file: " + keyFiles.head)
  keyFiles.head.getPrivateKey(password) match {
    case Success(pk) => priv = pk
    case Failure(e) => throw e
  }
  pubKeys += pub
  privKeys += priv

  //------------------------------------------------------------------------------------
  //Signed messages
  val messageBytes: Digest32 = Blake2b256("sameEntropic") //Should have same input to check determinism
  val messageToSign: Digest32 = Blake2b256(java.util.UUID.randomUUID.toString)

  it should "Match its signature to the expected sender private key" in {
      //Entropic input to input for pub/priv keypair
      val proof = sk1.sign(messageToSign)
      assert(PrivateKey25519.verify(messageToSign, pk1, proof))
  }

  it should "Yield an invalid signature if signed with incorrect foreign key" in {
      val proof = sk1.sign(messageToSign)
      assert(!PrivateKey25519.verify(messageToSign, pk2, proof))
  }

  it should "Sign and verify as expected when msg is deterministic" in {
      val proof = sk2.sign(messageBytes)
      //Utilize same input. Proving hashing function AND keygen methods are deterministic
      assert(PrivateKey25519.verify(messageBytes, pk2, proof))
  }
  //------------------------------------------------------------------------------------
  //Key Generation

   it should "Come as a private key and public key pair" in {
      //Entropic input used to make pub/priv keypair
      assert(sk1 != null)
      assert(pk1 != null)
      assert(sk1.isInstanceOf[PrivateKey25519])
      assert(pk1.isInstanceOf[PublicKey25519Proposition])
   }
  it should "Be deterministic" in {
      //Used the same entropic input
      assert(sk1.privKeyBytes === sk3.privKeyBytes)
      assert(pk1.equals(pk3))
  }

  it should "Have sufficient randomness in entropic keypair gen" in {
    //Valid pretest to ensure sufficiently random input so no shared keys
    assert(randomBytes1 != randomBytes2)
    assert(sk1 != null && pk1 != null && sk1.isInstanceOf[PrivateKey25519] && pk1.isInstanceOf[PublicKey25519Proposition])
  }

  //------------------------------------------------------------------------------------
  //KeyManager

  var pubKey: PublicKey25519Proposition = pk1
  val password2: String = "password2"

  it should "Have 2 keyfiles" in {
    assert(Keys.getListOfFiles(keyManager.defaultKeyDir).size == 1)
    keyManager.generateKeyFile(password2) match {
      case Success(pk) => pubKey = pk
      case Failure(ex) => throw new Error(s"An error occurred while creating a new keyfile. $ex")
    }
    keyFiles = Keys.getListOfFiles(keyManager.defaultKeyDir).map(file => KeyFile.readFile(file.getPath)).toSet
    println("first key file: " + keyFiles.head)
    println("second key file: " + keyFiles.tail.head)
    assert(keyFiles.size == 2)
    assert(keyManager.publicKeys.size == 2)
    assert(Keys.getListOfFiles(keyManager.defaultKeyDir).size == 2)
  }

  it should "Be locked" in {
    assert(keyManager.secrets.size == 2)
    keyManager.lockKeyFile(pubKey.address, password2).get
    assert(keyManager.secrets.size == 1)
  }

  it should "Be unlocked" in {
    keyManager.unlockKeyFile(pubKey.toString, password2)
    assert(keyManager.secrets.size == 2)
  }


  //------------------------------------------------------------------------------------
  //KeyFile

   //Export is formatted JSON to keystore file
  //TODO: How do you add a keyFile to Keys (KeyRing)?
  it should "grab list of files and convert to KeyFile" in {

    val exportedKeys: List[KeyFile] = Keys.getListOfFiles(keyManager.defaultKeyDir)
      .map(file => {
        KeyFile.readFile(file.getPath)
      })

    keyFiles.map(_.publicKeyFromAddress).foreach { pubKey =>
      assert(exportedKeys.map(_.publicKeyFromAddress).contains(pubKey))
    }
    assert (keyFiles.size == exportedKeys.size)
  }

  it should "Have keys stored in the proper format" in {
    val privKey = keyFiles.head.getPrivateKey(password2).get
    assert(privKey.isInstanceOf[PrivateKey25519])
    val pubKey = privKey.publicKeyBytes
    assert(pubKey.isInstanceOf[Array[Byte]])
  }
  it should "Be used to import keys" in {
    val privKey = keyFiles.head.getPrivateKey(password2).get
    assert(privKey.privKeyBytes === privKeys.head.privKeyBytes)
    val pubKey = privKey.publicKeyBytes
    assert(pubKey === pubKeys.head.pubKeyBytes)
  }

  //------------------------------------------------------------------------------------

}
