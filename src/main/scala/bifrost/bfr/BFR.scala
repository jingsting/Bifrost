package bifrost.bfr

import java.io.File

import bifrost.forging.ForgingSettings
import bifrost.scorexMod.GenericMinimalState.VersionTag
import bifrost.state.BifrostState.{BX, GSC}
import bifrost.transaction.box._
import bifrost.transaction.box.proposition.PublicKey25519Proposition
import bifrost.utils.ScorexLogging
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.crypto.encode.Base58

import scala.util.Try

case class BFR(bfrStore: LSMStore, stateStore: LSMStore) extends ScorexLogging {

  def closedBox(boxId: Array[Byte]): Option[BifrostBox] =
    stateStore.get(ByteArrayWrapper(boxId))
      .map(_.data)
      .map(BifrostBoxSerializer.parseBytes)
      .flatMap(_.toOption)

  def boxIdsByKey(publicKey: PublicKey25519Proposition): Seq[Array[Byte]] =
    boxIdsByKey(publicKey.pubKeyBytes)

  //Assumes that boxIds are fixed length equal to store's keySize (32 bytes)
  def boxIdsByKey(pubKeyBytes: Array[Byte]): Seq[Array[Byte]] =
    bfrStore
    .get(ByteArrayWrapper(pubKeyBytes))
    .map(_
      .data
      .grouped(stateStore.keySize)
      .toSeq)
    .getOrElse(Seq[Array[Byte]]())

    def boxesByKey(publicKey: PublicKey25519Proposition): Seq[BifrostBox] =
    boxesByKey(publicKey.pubKeyBytes)

  def boxesByKey(pubKeyBytes: Array[Byte]): Seq[BifrostBox] = {
    boxIdsByKey(pubKeyBytes)
      .map(id => closedBox(id))
      .filter {
        case box: Some[BifrostBox] => true
        case None => false
      }
      .map(_.get)
  }

  /**
    * @param newVersion - block id
    * @param changes - key filtered boxIdsToRemove and boxesToAppend extracted from block (in BifrostState)
    * @return - instance of updated BFR
    * (Note - makes use of vars for local variables since function remains a pure function and helps achieve better runtime)
    *
    *         Runtime complexity of below function is O(MN) + O(L)
    *         where M = Number of boxes to remove
    *         N = Number of boxes owned by a public key
    *         L = Number of boxes to append
    *
    */
  //noinspection ScalaStyle
  def updateFromState(newVersion: VersionTag, keyFilteredBoxIdsToRemove: Set[Array[Byte]], keyFilteredBoxesToAdd: Set[BifrostBox]): Try[BFR] = Try {
    log.debug(s"${Console.GREEN} Update BFR to version: ${Base58.encode(newVersion)}${Console.RESET}")

    /* This seeks to avoid the scenario where there is remove and then update of the same keys */
    val boxIdsToRemove: Set[ByteArrayWrapper] = (keyFilteredBoxIdsToRemove -- keyFilteredBoxesToAdd.map(b => b.id)).map(ByteArrayWrapper.apply)

    var boxesToRemove: Map[Array[Byte], Array[Byte]] = Map()
    var boxesToAppend: Map[Array[Byte], Array[Byte]] = Map()
    //Getting set of public keys for boxes being removed and appended
    //Using ByteArrayWrapper for sets since equality method uses a deep compare unlike a set of byte arrays
    val keysSet: Set[ByteArrayWrapper] = {
      boxIdsToRemove
        .flatMap(boxId => closedBox(boxId.data))
        .foreach(box => box match {
          case box: BifrostPublic25519NoncedBox =>
            boxesToRemove += (box.id -> box.proposition.pubKeyBytes)
          //For boxes that do not follow the BifrostPublicKey25519NoncedBox (are not token boxes) - do nothing
          case _ =>
        })

      keyFilteredBoxesToAdd
        .foreach({
          case box: BifrostPublic25519NoncedBox =>
            boxesToAppend += (box.id -> box.proposition.pubKeyBytes)
          //For boxes that do not follow the BifrostPublicKey25519NoncedBox (are not token boxes) - do nothing
          case _ =>
        })

      (boxesToRemove.map(boxToKey => ByteArrayWrapper(boxToKey._2)) ++ boxesToAppend.map(boxToKey => ByteArrayWrapper(boxToKey._2))).toSet
    }

    //Get old boxIds list for each of the above public keys
    var keysToBoxIds: Map[ByteArrayWrapper, Seq[Array[Byte]]] = keysSet.map(
      publicKey => publicKey -> boxIdsByKey(publicKey.data)
    ).toMap

    //For each box in temporary map match against public key and remove/append to boxIdsList in original keysToBoxIds map
    for((boxId, publicKey) <- boxesToRemove) {
      keysToBoxIds += (ByteArrayWrapper(publicKey) -> keysToBoxIds(ByteArrayWrapper(publicKey)).filterNot(_ sameElements boxId))
    }
    for((boxId, publicKey) <- boxesToAppend) {
      //Prepending to list is O(1) while appending is O(n)
      keysToBoxIds += (ByteArrayWrapper(publicKey) -> (boxId +: keysToBoxIds(ByteArrayWrapper(publicKey))))
    }

    bfrStore.update(
      ByteArrayWrapper(newVersion),
      Seq(),
      keysToBoxIds.map(element =>
        element._1 -> ByteArrayWrapper(element._2.flatten.toArray))
    )

    BFR(bfrStore, stateStore)
  }

  def rollbackTo(version: VersionTag, stateStore: LSMStore): Try[BFR] = Try {
    if (bfrStore.lastVersionID.exists(_.data sameElements version)) {
      this
    } else {
      log.debug(s"Rolling back BFR to: ${Base58.encode(version)}")
      bfrStore.rollback(ByteArrayWrapper(version))
      BFR(bfrStore, stateStore)
    }
  }

}

object BFR extends ScorexLogging {

  def apply(s1: LSMStore, s2: LSMStore) : BFR = {
    new BFR (s1, s2)
  }

  def readOrGenerate(settings: ForgingSettings, stateStore: LSMStore): Option[BFR] = {
    val bfrDirOpt = settings.bfrDirOpt
    val logDirOpt = settings.logDirOpt
    bfrDirOpt.map(readOrGenerate(_, logDirOpt, settings, stateStore))
  }

  def readOrGenerate(bfrDir: String, logDirOpt: Option[String], settings: ForgingSettings, stateStore: LSMStore): BFR = {
    val iFile = new File(s"$bfrDir")
    iFile.mkdirs()
    val bfrStore = new LSMStore(iFile)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        log.info("Closing bfr storage...")
        bfrStore.close()
      }
    })

    BFR(bfrStore, stateStore)
  }

}