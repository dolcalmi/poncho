import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.concurrent.Future
import scodec.bits.ByteVector
import scodec.codecs.uint16
import scoin._
import scoin.Crypto.{PrivateKey, PublicKey}
import scoin.ln.{LightningMessage, Bolt11Invoice}

trait NodeInterface {
  def privateKey: PrivateKey
  def publicKey: PublicKey

  def inspectOutgoingPayment(
      identifier: HtlcIdentifier,
      paymentHash: ByteVector32
  ): Future[PaymentStatus]

  def sendCustomMessage(
      peerId: ByteVector,
      message: LightningMessage
  ): Future[ujson.Value]

  def sendOnion(
      chan: Channel,
      htlcId: Long,
      paymentHash: ByteVector32,
      firstHop: ShortChannelId,
      amount: MilliSatoshi,
      cltvExpiry: CltvExpiry,
      onion: ByteVector
  ): Unit

  def getAddress(): Future[String]
  def getCurrentBlock(): Future[BlockHeight]
  def getBlockByHeight(height: BlockHeight): Future[Block]
  def getChainHash(): Future[ByteVector32]

  def main(onInit: () => Unit): Unit
}
