package bifrost.modifier.box.serialization

import java.util.UUID

import bifrost.modifier.box.{ExecutionBox, ProgramBox}
import bifrost.utils.Extensions._
import bifrost.utils.serialization.{BifrostSerializer, Reader, Writer}
import com.google.common.primitives.Longs

object ExecutionBoxSerializer extends BifrostSerializer[ExecutionBox] {

  override def serialize(obj: ExecutionBox, w: Writer): Unit = {
    ProgramBoxSerializer.serialize(obj, w)

    /* stateBoxUUIDs: Seq[UUID], List of uuids of state boxes from ProgramBoxRegistry */
    w.putUInt(obj.stateBoxUUIDs.length)
    obj.stateBoxUUIDs.foreach { id =>
      // The SignificantBits could be negative longs
      w.putLong(obj.value.getMostSignificantBits)
      w.putLong(obj.value.getLeastSignificantBits)
    }

    /* codeBoxIds: Seq[Array[Byte]] */
    w.putUInt(obj.codeBoxIds.length)
    obj.codeBoxIds.foreach{id =>
      w.putUInt(id.length)
      w.putBytes(id)
    }
  }

  override def parse(r: Reader): ExecutionBox = {
    val programBox: ProgramBox = ProgramBoxSerializer.parse(r)

    /* stateBoxUUIDs: Seq[UUID], List of uuids of state boxes from ProgramBoxRegistry */
    val stateBoxUUIDsLength: Int = r.getUInt().toIntExact
    println(s"\n>>>>>>>>>>>>>>>>>>>>> input: ${stateBoxUUIDsLength}")
    val stateBoxUUIDs: Seq[UUID] = (0 until stateBoxUUIDsLength).map(i => {
      new UUID(r.getLong(), r.getLong())
    })

    /* codeBoxIds: Seq[Array[Byte]] */
    val codeBoxIdsLength: Int = r.getUInt().toIntExact

    val codeBoxIds: Seq[Array[Byte]] = (0 until codeBoxIdsLength).map{_ =>
      val idLength: Int = r.getUInt().toIntExact
      r.getBytes(idLength)
    }

    ExecutionBox(programBox.proposition, programBox.nonce, programBox.value, stateBoxUUIDs, codeBoxIds)
  }
}
