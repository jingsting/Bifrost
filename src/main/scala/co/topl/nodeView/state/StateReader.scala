package co.topl.nodeView.state

import co.topl.modifier.BoxReader
import co.topl.nodeView.NodeViewComponent
import co.topl.nodeView.state.MinimalState.VersionTag
import co.topl.modifier.box._

import scala.reflect.ClassTag

trait StateReader[KP, KT] extends BoxReader[KP, KT] with NodeViewComponent {

  type ProgramKey = KP
  type TokenKey = KT

  //must be ID of last applied modifier
  def version: VersionTag

  def getBox(id: BoxId): Option[Box[_]]

  def getProgramBox[PBX <: ProgramBox: ClassTag](key: KP): Option[PBX]

  def getTokenBoxes(key: KT): Option[Seq[TokenBox[TokenValueHolder]]]

}
