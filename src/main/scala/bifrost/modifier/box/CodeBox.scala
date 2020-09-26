package bifrost.modifier.box

import bifrost.crypto.FastCryptographicHash
import bifrost.modifier.box.proposition.PublicKey25519Proposition
import bifrost.state.ProgramId
import com.google.common.primitives.Longs
import io.circe.syntax._
import io.circe.{ Decoder, HCursor, Json }
import scorex.crypto.encode.Base58

case class CodeBox(override val proposition: PublicKey25519Proposition,
                   override val nonce: Long,
                   override val value: ProgramId,
                   code: Seq[String], // List of strings of JS functions
                   interface: Map[String, Seq[String]]
                   ) extends ProgramBox(proposition, nonce, value) {

  override lazy val typeOfBox: String = "CodeBox"

  override lazy val id: Array[Byte] = CodeBox.idFromBox(proposition, nonce)

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "type" -> typeOfBox.asJson,
    "proposition" -> Base58.encode(proposition.pubKeyBytes).asJson,
    "nonce" -> nonce.toString.asJson,
    "programId" -> value.toString.asJson,
    "code" -> code.asJson,
    "interface" -> interface.map(ci => ci._1 -> ci._2.asJson).asJson
  ).asJson
}

object CodeBox {

  def idFromBox[proposition <: PublicKey25519Proposition](prop: proposition, nonce: Long): Array[Byte] =
    FastCryptographicHash(prop.pubKeyBytes ++ "code".getBytes ++ Longs.toByteArray(nonce))

  implicit val decodeCodeBox: Decoder[CodeBox] = (c: HCursor) => for {
    proposition <- c.downField("proposition").as[String]
    value <- c.downField("uuid").as[String]
    code <- c.downField("code").as[Seq[String]]
    interface <- c.downField("interface").as[Map[String, Seq[String]]]
    nonce <- c.downField("nonce").as[Long]
  } yield {
    val prop = PublicKey25519Proposition(proposition).get
    val progId = ProgramId(value).get
    CodeBox(prop, nonce, progId, code, interface)
  }
}
