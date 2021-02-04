package co.topl.modifier

import co.topl.attestation.{Address, PrivateKeyCurve25519}
import co.topl.modifier.block.BloomFilter.BloomTopic
import co.topl.modifier.block.{BloomFilter, TransactionsCarryingPersistentNodeViewModifier}
import co.topl.modifier.transaction.Transaction
import co.topl.utils.ValidGenerators
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks, ScalaCheckPropertyChecks}
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.Curve25519
import scorex.util.encode.Base58

class BloomFilterSpec
    extends AnyPropSpec
    with ScalaCheckPropertyChecks
    with ScalaCheckDrivenPropertyChecks
    with ValidGenerators
    with Matchers {

  property("Bloomfilter should be able to tell if it definitely contains an address(false negatives)") {
    forAll(validBifrostTransactionSeqGen) { txs =>
      val bloomfilter: BloomFilter = TransactionsCarryingPersistentNodeViewModifier.createBloom(txs)

      txs.foreach { tx =>
        tx.bloomTopics.foreach { bt =>
          bloomfilter.contains(bt) shouldBe true
        }
      }
    }
  }

  property("Bloomfilter should be able to tell if an address is likely not in the block(false positives)") {
    forAll(validBifrostTransactionSeqGen) { txs =>
      val bloomfilter: BloomFilter = TransactionsCarryingPersistentNodeViewModifier.createBloom(txs.dropRight(1))
      val addressCount = txs.foldLeft(0)(_ + _.bloomTopics.size)
      val addressInBloom: Int = txs.dropRight(1).foldLeft(0)(_ + _.bloomTopics.size)
      val numAddressLastTx: Int = txs.last.bloomTopics.size

      val falsePositives = txs.last.bloomTopics.foldLeft(0) { (count, bt) =>
        if (bloomfilter.contains(bt)) count + 1
        else count
      }

      /** Sometimes there's very few addresses in the last transaction, we only test here to make sure we don't get too
        * many false positives. There's a very slight chance that this will break
        */
      (falsePositives <= numAddressLastTx / 2) shouldBe true
    }
  }

  /** See: https://hur.st/bloomfilter/?n=500&p=&m=2048&k=4
    * Assuming that there will be 500 address in the bloom filter:
    *   the probability of false positives will be 0.151 (1 in 7)
    */
  property("The probability of false positives in bloomfilter with 500 addresses should be lower than 0.15") {
    /** The generated addresses are made deterministic, so that the variation won't break the test */
    val rand = new scala.util.Random(1)

    val randAddr: Seq[Address] =
      (0 until 600)
        .map(_ => Array.fill(Curve25519.KeyLength)((rand.nextInt(256) - 128).toByte))
        .map(s => PrivateKeyCurve25519.secretGenerator.generateSecret(s)._2)
        .map(k => k.address)

    val bloomTopics: Set[BloomTopic] = randAddr.take(500).map(addr => BloomTopic @@ addr.bytes).toSet
    val bloomfilter: BloomFilter = BloomFilter(bloomTopics)
    val testTopics: Seq[BloomTopic] = randAddr.drop(500).map(addr => BloomTopic @@ addr.bytes)

    val falsePositives = testTopics.foldLeft(0) { (count, bt) =>
      if (bloomfilter.contains(bt)) count + 1
      else count
    }

    (falsePositives <= testTopics.size * 0.15) shouldBe true
  }
}