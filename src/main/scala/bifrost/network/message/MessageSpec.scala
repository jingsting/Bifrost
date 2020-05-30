package bifrost.network.message

import bifrost.settings.Version
import bifrost.serialization.Serializer

/**
  * Base trait for app p2p messages in the network
  */
trait MessageSpec[Content] extends Serializer[Content] {

  /**
    * The p2p protocol version in which this message type first appeared
    */
  val protocolVersion: Version

  /**
    * Code which identifies what message type is contained in the payload
    */

  val messageCode: Message.MessageCode

  /**
    * Name of this message type. For debug purposes only.
    */
  val messageName: String

  override def toString: String = s"MessageSpec($messageCode: $messageName)"
}

/**
  * P2p messages, that where implemented since the beginning.
  */
trait MessageSpecV1[Content] extends MessageSpec[Content] {

  override val protocolVersion: Version = Version.initial

}