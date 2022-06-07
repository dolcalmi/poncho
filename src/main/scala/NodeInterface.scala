import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.concurrent.Future

import scodec.bits.ByteVector
import scodec.codecs.uint16

import codecs.HostedChannelCodecs._
import codecs._

trait NodeInterface {
  def getPrivateKey(): ByteVector32
  def ourPubKey: ByteVector

  def inspectOutgoingPayment(
      peerId: ByteVector,
      htlcId: ULong,
      paymentHash: ByteVector32
  ): Future[PaymentStatus]

  def sendCustomMessage(
      peerId: ByteVector,
      message: HostedServerMessage | HostedClientMessage
  ): Future[ujson.Value]

  def sendOnion(
      chan: Channel[_, _],
      htlcId: ULong,
      paymentHash: ByteVector32,
      firstHop: ShortChannelId,
      amount: MilliSatoshi,
      cltvExpiryDelta: CltvExpiryDelta,
      onion: ByteVector
  ): Unit

  def getCurrentBlock(): Future[BlockHeight]
  def getChainHash(): Future[ByteVector32]

  def main(onInit: () => Unit): Unit
}
