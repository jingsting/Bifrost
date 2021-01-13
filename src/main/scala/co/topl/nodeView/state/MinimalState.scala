package co.topl.nodeView.state

/**
  * Created by cykoz on 4/13/17.
  */

import co.topl.modifier.ModifierId
import co.topl.modifier.block.PersistentNodeViewModifier
import co.topl.nodeView.NodeViewComponent
import co.topl.nodeView.state.MinimalState.VersionTag

import scala.util.Try

/**
  * Abstract functional interface of state which is a result of a sequential blocks applying
  */

trait MinimalState[M <: PersistentNodeViewModifier, MS <: MinimalState[M, MS]]
    extends NodeViewComponent with StateReader{

  self: MS =>

  def version: VersionTag

  def applyModifier(mod: M): Try[MS]

  def rollbackTo(version: VersionTag): Try[MS]

  def getReader: StateReader = this
}

object MinimalState {
  type VersionTag = ModifierId
}
