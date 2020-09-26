package bifrost.state

import java.io.File

import bifrost.settings.AppSettings
import bifrost.state.MinimalState.VersionTag
import bifrost.utils.Logging
import io.iohk.iodb.{ ByteArrayWrapper, LSMStore }

import scala.util.Try

/**
 * A registry containing mapping from fixed programId -> changing boxId
 *
 * @param storage Persistent storage object for saving the ProgramBoxRegistry to disk
 */
case class ProgramBoxRegistry(storage: LSMStore) extends Registry[ProgramBoxRegistry.K, ProgramBoxRegistry.V] {

  import ProgramBoxRegistry.{ K, V }

  //----- input and output transformation functions
  override def registryInput (key: K): Array[Byte] = key.hashBytes

  override def registryOutput (value: Array[Byte]): V = BoxId(value)

  /**
   * @param newVersion - block id
   * @param toRemove map of public keys to a sequence of boxIds that should be removed
   * @param toAppend map of public keys to a sequence of boxIds that should be added
   * @return - instance of updated ProgramBoxRegistry
   *
   *         Runtime complexity of below function is O(MN) + O(L)
   *         where M = Number of boxes to remove
   *         N = Number of boxes owned by a public key
   *         L = Number of boxes to append
   *
   */
  def update ( newVersion: VersionTag,
               toRemove: Map[K, Seq[V]],
               toAppend: Map[K, Seq[V]]
             ): Try[ProgramBoxRegistry] = {

    Try {
      log.debug(s"${Console.GREEN} Update ProgramBoxRegistry to version: ${newVersion.toString}${Console.RESET}")

      // look for addresses that will be empty after the update
      val (deleted: Seq[K], updated: Seq[(K, V)]) = {
        // make a list of all accounts to consider then loop through them and determine their new state
        (toRemove.keys ++ toAppend.keys).map(key => {
          val current = lookup(key).head

          // case where the program id no longer exists
          if ( current == toRemove(key).head && toAppend(key).isEmpty ) {
            (Some(key), None)

            // case where the boxId must be updated
          } else {
            (None, Some((key, toAppend(key).head)))
          }
        })
      }.foldLeft((Seq[K](), Seq[(K, V)]()))(( acc, progId ) => (acc._1 ++ progId._1, acc._2 ++ progId._2))

      storage.update(
        ByteArrayWrapper(newVersion.hashBytes),
        deleted.map(k => ByteArrayWrapper(registryInput(k))),
        updated.map(elem => ByteArrayWrapper(registryInput(elem._1)) -> ByteArrayWrapper(elem._2.hashBytes))
        )

      ProgramBoxRegistry(storage)
    }
  }


  def rollbackTo(version: VersionTag): Try[ProgramBoxRegistry] = Try {
    if (storage.lastVersionID.exists(_.data sameElements version.hashBytes)) {
      this
    } else {
      log.debug(s"Rolling back ProgramBoxRegistry to: ${version.toString}")
      storage.rollback(ByteArrayWrapper(version.hashBytes))
      ProgramBoxRegistry(storage)
    }
  }

}

object ProgramBoxRegistry extends Logging {

  type K = ProgramId
  type V = BoxId

  def readOrGenerate ( settings: AppSettings ): Option[ProgramBoxRegistry] = {
    if (settings.enablePBR) {
      val dataDir = settings.dataDir.ensuring(_.isDefined, "data dir must be specified").get

      val iFile = new File(s"$dataDir/programBoxRegistry")
      iFile.mkdirs()
      val storage = new LSMStore(iFile)

      Some(new ProgramBoxRegistry(storage))
    } else None
  }
}
