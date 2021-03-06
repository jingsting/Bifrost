package co.topl.modifier.transaction.serialization

import co.topl.attestation._
import co.topl.attestation.serialization.{ProofSerializer, PropositionSerializer}
import co.topl.modifier.transaction.PolyTransfer
import co.topl.modifier.box.TokenValueHolder
import co.topl.utils.Int128
import co.topl.utils.serialization.Extensions._
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

import scala.language.existentials

object PolyTransferSerializer extends BifrostSerializer[PolyTransfer[_ <: Proposition]] {

  override def serialize(obj: PolyTransfer[_ <: Proposition], w: Writer): Unit = {
    /* Byte */ //this is used to signal the types of propositions in the transactions
    w.put(obj.getPropIdentifier.typePrefix)

    /* from: IndexedSeq[(Address, Nonce)] */
    w.putUInt(obj.from.length)
    obj.from.foreach { case (addr, nonce) =>
      Address.serialize(addr, w)
      w.putLong(nonce)
    }

    /* to: IndexedSeq[(Address, Long)] */
    w.putUInt(obj.to.length)
    obj.to.foreach { case (addr, value) =>
      Address.serialize(addr, w)
      TokenValueHolder.serialize(value, w)
    }

    /* signatures: Map[Proposition, Proof] */
    w.putUInt(obj.attestation.size)
    obj.attestation.foreach { case (prop, sig) =>
      PropositionSerializer.serialize(prop, w)
      ProofSerializer.serialize(sig, w)
    }

    /* fee: Int128 */
    w.putInt128(obj.fee)

    /* timestamp: Long */
    w.putULong(obj.timestamp)

    /* data: Option[String] */
    w.putOption(obj.data) { (writer, d) =>
      writer.putByteString(d)
    }

    /* minting: Boolean */
    w.putBoolean(obj.minting)
  }

  override def parse(r: Reader): PolyTransfer[_ <: Proposition] = {
    val propTypePrefix = r.getByte()

    val fromLength: Int = r.getUInt().toIntExact
    val from = (0 until fromLength).map { _ =>
      val addr = Address.parse(r)
      val nonce = r.getLong()
      addr -> nonce
    }

    val toLength: Int = r.getUInt().toIntExact
    val to = (0 until toLength).map { _ =>
      val addr = Address.parse(r)
      val value = TokenValueHolder.parse(r)
      addr -> value
    }

    val signaturesLength: Int = r.getUInt().toIntExact
    val signatures = Map((0 until signaturesLength).map { _ =>
      val prop = PropositionSerializer.parse(r)
      val sig = ProofSerializer.parse(r)
      prop -> sig
    }: _*)

    val fee: Int128 = r.getInt128()
    val timestamp: Long = r.getULong()

    val data: Option[String] = r.getOption {
      r.getByteString()
    }

    val minting: Boolean = r.getBoolean()

    propTypePrefix match {
      case PublicKeyPropositionCurve25519.`typePrefix` =>
        val sigs = signatures.asInstanceOf[Map[PublicKeyPropositionCurve25519, SignatureCurve25519]]
        PolyTransfer(from, to, sigs, fee, timestamp, data, minting)

      case ThresholdPropositionCurve25519.`typePrefix` =>
        val sigs = signatures.asInstanceOf[Map[ThresholdPropositionCurve25519, ThresholdSignatureCurve25519]]
        PolyTransfer(from, to, sigs, fee, timestamp, data, minting)
    }
  }
}
