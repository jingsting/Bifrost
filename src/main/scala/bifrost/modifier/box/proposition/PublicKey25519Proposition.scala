package bifrost.modifier.box.proposition

import bifrost.crypto.FastCryptographicHash._
import bifrost.crypto.PrivateKey25519
import bifrost.state.ProgramId
import bifrost.utils.serialization.BifrostSerializer
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.Curve25519

import scala.util.{ Failure, Success, Try }

case class PublicKey25519Proposition(pubKeyBytes: Array[Byte]) extends ProofOfKnowledgeProposition[PrivateKey25519] {

  require(pubKeyBytes.length == Curve25519.KeyLength,
    s"Incorrect pubKey length, ${Curve25519.KeyLength} expected, ${pubKeyBytes.length} found")

  import PublicKey25519Proposition._

  override type M = PublicKey25519Proposition

  lazy val address: String = Base58.encode(bytesWithVersion ++ calcCheckSum(bytesWithVersion))

  private def bytesWithVersion: Array[Byte] = AddressVersion +: pubKeyBytes

  override def serializer: BifrostSerializer[PublicKey25519Proposition] = PublicKey25519PropositionSerializer

  override def toString: String = address

  override def equals(obj: Any): Boolean = obj match {
    case p: PublicKey25519Proposition => p.pubKeyBytes sameElements pubKeyBytes
    case _ => false
  }

  override def hashCode(): Int = (BigInt(Blake2b256(pubKeyBytes)) % Int.MaxValue).toInt

  def verify(message: Array[Byte], signature: Array[Byte]): Boolean = Curve25519.verify(signature, message, pubKeyBytes)
}



object PublicKey25519Proposition {

  val AddressVersion: Byte = 1
  val ChecksumLength = 4
  val AddressLength = 1 + Constants25519.PubKeyLength + ChecksumLength

  def apply(id: String): Try[PublicKey25519Proposition] = {
    Try {
      Base58.decode(id) match {
        case Success(id) => new PublicKey25519Proposition(id)
        case Failure(ex) => throw ex
      }
    }
  }

  def calcCheckSum(bytes: Array[Byte]): Array[Byte] = hash(bytes).take(ChecksumLength)

  def validPubKey(address: String): Try[PublicKey25519Proposition] =
    Base58.decode(address).flatMap { addressBytes =>
      if (addressBytes.length != AddressLength)
        Failure(new Exception("Wrong address length"))
      else {
        val checkSum = addressBytes.takeRight(ChecksumLength)

        val checkSumGenerated = calcCheckSum(addressBytes.dropRight(ChecksumLength))

        if (checkSum.sameElements(checkSumGenerated))
          Success(PublicKey25519Proposition(addressBytes.dropRight(ChecksumLength).tail))
        else Failure(new Exception("Wrong checksum"))
      }
    }
}

object Constants25519 {
  val PrivKeyLength = 32
  val PubKeyLength = 32
}
