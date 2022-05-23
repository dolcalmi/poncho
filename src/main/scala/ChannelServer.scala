import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import scala.scalanative.unsigned._
import com.softwaremill.quicklens._
import castor.Context
import upickle.default.{ReadWriter, macroRW}

import codecs._
import crypto.Crypto
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import scodec.bits.ByteVector
import scodec.codecs._

case class FailureOnion(onion: ByteVector)
case class FailureCode(code: String)
type PaymentFailure = FailureOnion | FailureCode
type PaymentPreimage = ByteVector32
type HTLCResult =
  Option[Either[PaymentFailure, PaymentPreimage]]

class ChannelServer(peerId: String)(implicit
    ac: castor.Context
) extends castor.SimpleActor[HostedClientMessage] {
  sealed trait State
  case class Inactive() extends State
  case class Opening(refundScriptPubKey: ByteVector) extends State
  case class Active(
      lcssNext: Option[LastCrossSignedState],
      htlcResults: Map[ByteVector32, Promise[HTLCResult]]
  ) extends State
  case class Errored(lcssNext: Option[LastCrossSignedState]) extends State
  case class Overriding(target: LastCrossSignedState) extends State

  var state: State =
    Database.data.channels.get(peerId) match {
      case Some(chandata) if chandata.isActive =>
        Active(lcssNext = None, htlcResults = Map.empty)
      case _ => Inactive()
    }

  def stay = state

  def sendMessage: HostedServerMessage => Future[ujson.Value] =
    Main.node.sendCustomMessage(peerId, _)

  def run(msg: HostedClientMessage): Unit = {
    Main.log(s"[$this] at $state <-- $msg")
    state = (state, msg) match {
      // someone wants a new hosted channel from us
      case (Inactive(), msg: InvokeHostedChannel) => {
        // check chain hash
        if (msg.chainHash != Main.chainHash) {
          Main.log(
            s"[${peerId}] sent InvokeHostedChannel for wrong chain: ${msg.chainHash} (current: ${Main.chainHash})"
          )
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
              s"invalid chainHash (local=${Main.chainHash} remote=${msg.chainHash})"
            )
          )
          stay
        } else {
          // chain hash is ok, proceed
          Database.data.channels.get(peerId) match {
            case Some(chandata) => {
              // channel already exists, so send last cross-signed-state
              sendMessage(chandata.lcss)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
            case None => {
              // reply saying we accept the invoke
              sendMessage(Main.ourInit)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
          }
        }
      }

      // final step of channel open process
      case (Opening(refundScriptPubKey), msg: StateUpdate) => {
        // build last cross-signed state
        val lcss = LastCrossSignedState(
          isHost = true,
          refundScriptPubKey = refundScriptPubKey,
          initHostedChannel = Main.ourInit,
          blockDay = msg.blockDay,
          localBalanceMsat =
            Main.ourInit.channelCapacityMsat - Main.ourInit.initialClientBalanceMsat,
          remoteBalanceMsat = Main.ourInit.initialClientBalanceMsat,
          localUpdates = 0L,
          remoteUpdates = 0L,
          incomingHtlcs = Nil,
          outgoingHtlcs = Nil,
          localSigOfRemote = ByteVector64.Zeroes,
          remoteSigOfLocal = msg.localSigOfRemoteLCSS
        )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        // check if everything is ok
        if ((msg.blockDay - Main.currentBlockDay).abs > 1) {
          Main.log(
            s"[${peerId}] sent StateUpdate with wrong blockday: ${msg.blockDay} (current: ${Main.currentBlockDay})"
          )
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_BLOCKDAY
            )
          )
          Inactive()
        } else if (!lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
          Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          )
          Inactive()
        } else {
          // all good, save this channel to the database and consider it opened
          Database.update { data =>
            {
              data
                .modify(_.channels)
                .using(
                  _ +
                    (
                      peerId -> ChannelData(
                        isActive = true,
                        error = None,
                        lcss = lcss,
                        proposedOverride = None
                      )
                    )
                )
            }
          }

          // send our signed state update
          sendMessage(lcss.stateUpdate)

          // send a channel update
          sendMessage(ChanTools.makeChannelUpdate(peerId, lcss))

          Active(None, Map.empty)
        }
      }

      // a client was just turned on and is sending this to sync states
      case (Active(_, _), msg: LastCrossSignedState) => {
        val isLocalSigOk = msg.verifyRemoteSig(Main.node.ourPubKey)
        val isRemoteSigOk =
          msg.reverse.verifyRemoteSig(ByteVector.fromValidHex(peerId))

        if (!isLocalSigOk || !isRemoteSigOk) {
          val err = if (!isLocalSigOk) {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with a signature that isn't ours"
            )
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_LOCAL_SIG
            )
          } else {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with an invalid signature"
            )
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          }
          sendMessage(err)
          Database.update { data =>
            {
              data
                .modify(_.channels.at(peerId).isActive)
                .setTo(false)
            }
          }
          Inactive()
        } else {
          // channel is active, which means we must have a database entry necessarily
          val chandata = Database.data.channels.get(peerId).get

          val lcssMostRecent =
            if (
              (chandata.lcss.localUpdates + chandata.lcss.remoteUpdates) >=
                (msg.remoteUpdates + msg.localUpdates)
            ) {
              // we are even or ahead
              chandata.lcss
            } else {
              // we are behind
              Main.log(
                s"[${peerId}] sent LastCrossSignedState showing that we are behind: " +
                  s"local=${chandata.lcss.localUpdates}/${chandata.lcss.remoteUpdates} " +
                  s"remote=${msg.remoteUpdates}/${msg.localUpdates}"
              )

              // save their lcss here
              Database.update { data =>
                {
                  data
                    .modify(_.channels.at(peerId).lcss)
                    .setTo(msg)
                }
              }

              msg
            }

          // all good, send the most recent lcss again and then the channel update
          sendMessage(lcssMostRecent)
          sendMessage(ChanTools.makeChannelUpdate(peerId, lcssMostRecent))
          stay
        }
      }

      // a client has lost its channel state but we have it
      case (Active(_, _), msg: InvokeHostedChannel) => {
        // channel already exists, so send last cross-signed-state
        val chandata = Database.data.channels.get(peerId).get
        sendMessage(chandata.lcss)
        stay
      }

      // client is sending an htlc through us
      case (Active(maybeLcssNext, htlcResults), msg: UpdateAddHtlc) => {
        stay
      }

      // client is fulfilling an HTLC we've sent
      case (Active(maybeLcssNext, htlcResults), msg: UpdateFulfillHtlc) => {
        val chandata = Database.data.channels.get(peerId).get

        // create (or modify) new lcss to be our next
        val baseLcssNext = maybeLcssNext
          .getOrElse(chandata.lcss)

        // find the htlc
        baseLcssNext.outgoingHtlcs.find(htlc =>
          htlc.paymentHash == Crypto.sha256(msg.paymentPreimage)
        ) match {
          case Some(htlc) => {
            val lcssNext =
              baseLcssNext
                .copy(
                  localBalanceMsat =
                    baseLcssNext.localBalanceMsat - htlc.amountMsat,
                  localUpdates = baseLcssNext.localUpdates + 1,
                  outgoingHtlcs =
                    baseLcssNext.outgoingHtlcs.filter(_.id == htlc.id),
                  remoteSigOfLocal = ByteVector64.Zeroes
                )
                .withLocalSigOfRemote(Main.node.getPrivateKey())

            // check if this new potential lcss is not stupid
            if (ChanTools.lcssIsBroken(lcssNext)) {
              // TODO what is happening?
              stay
            } else {
              // send state_update
              sendMessage(lcssNext.stateUpdate)

              // call our htlc callback so our node is notified
              htlcResults
                .get(htlc.paymentHash)
                .foreach(_.success(Some(Right(msg.paymentPreimage))))

              // update state (lcssNext, plus remove this from the callbacks we're keeping track of)
              Active(
                lcssNext = Some(lcssNext),
                htlcResults =
                  htlcResults.filter((hash, _) => hash != htlc.paymentHash)
              )
            }
          }
          case None => {
            Main.log(
              s"client has fulfilled an HTLC we don't know about: ${Crypto
                  .sha256(msg.paymentPreimage)}"
            )
            stay
          }
        }
      }

      // client is failing an HTLC we've sent
      case (
            Active(maybeLcssNext, htlcResults),
            msg: (UpdateFailHtlc | UpdateFailMalformedHtlc)
          ) => {
        stay
      }

      // after an HTLC has been sent or received or failed or fulfilled and we've updated our local state,
      // this should be the confirmation that the other side has also updated it correctly
      // TODO this should account for situations in which peer is behind us (ignore?) and for when we're behind (keep track of the forward state?)
      case (Active(Some(lcssNext), _), msg: StateUpdate) => {
        if (
          msg.remoteUpdates == lcssNext.localUpdates &&
          msg.localUpdates == lcssNext.remoteUpdates &&
          msg.blockDay == lcssNext.blockDay
        ) {
          // it seems ok
          val lcss = lcssNext.copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)
          if (lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            // update state on the database
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcss, proposedOverride = None, error = None)
                )
            }
          } else stay
        } else stay

        stay
      }

      // client is (hopefully) accepting our override proposal
      case (Overriding(lcssOverrideProposal), msg: StateUpdate) => {
        if (
          msg.remoteUpdates == lcssOverrideProposal.localUpdates &&
          msg.localUpdates == lcssOverrideProposal.remoteUpdates &&
          msg.blockDay == lcssOverrideProposal.blockDay
        ) {
          // it seems that the peer has agreed to our override proposal
          val lcss = lcssOverrideProposal.copy(remoteSigOfLocal =
            msg.localSigOfRemoteLCSS
          )
          if (lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            // update state on the database
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcss, proposedOverride = None, error = None)
                )
            }

            // send our channel policies again just in case
            sendMessage(ChanTools.makeChannelUpdate(peerId, lcss))

            // channel is active again
            Active(None, Map.empty)
          } else stay
        } else stay
      }

      // client is sending an error
      case (Active(maybeLcssNext, _), msg: Error) => {
        Database.update { data =>
          data.modify(_.channels.at(peerId).error).setTo(Some(msg))
        }
        Errored(lcssNext = maybeLcssNext)
      }

      case _ => stay
    }
  }

  def addHTLC(prototype: UpdateAddHtlc): Future[HTLCResult] = {
    var promise = Promise[HTLCResult]()

    Main.log(s"forwarding payment $state <-- $prototype")

    state match {
      case Active(maybeLcssNext, htlcResults) => {
        val chandata = Database.data.channels.get(peerId).get

        // create update_add_htlc based on the prototype we've received
        val msg = prototype.copy(id =
          maybeLcssNext
            .map(_.localUpdates)
            .getOrElse(0L)
            .toULong + 1L.toULong
        )

        // create (or modify) new lcss to be our next
        val lcssNext = maybeLcssNext
          .getOrElse(chandata.lcss)
          .pipe(baseLcssNext =>
            baseLcssNext
              .copy(
                blockDay = Main.currentBlockDay,
                localBalanceMsat =
                  baseLcssNext.localBalanceMsat - msg.amountMsat,
                localUpdates = baseLcssNext.localUpdates + 1,
                outgoingHtlcs = baseLcssNext.outgoingHtlcs :+ msg,
                remoteSigOfLocal = ByteVector64.Zeroes
              )
              .withLocalSigOfRemote(Main.node.getPrivateKey())
          )

        // TODO check if fees are sufficient

        // check if this new potential lcss is not stupid
        if (!ChanTools.lcssIsBroken(lcssNext)) {
          // TODO provide the correct failure message here
          promise.success(Some(Left(FailureCode("2002"))))
        } else {
          // send update_add_htlc
          sendMessage(msg)
            .onComplete {
              case Failure(err) =>
                promise.success(None)
              case _ => {}
            }

          // send state_update
          sendMessage(lcssNext.stateUpdate)

          // update callbacks we're keeping track of
          state = Active(
            lcssNext = Some(lcssNext),
            htlcResults = htlcResults + (msg.paymentHash -> promise)
          )
        }
      }
      case _ => {}
    }

    promise.future
  }

  def stateOverride(newLocalBalance: MilliSatoshi): Future[String] = {
    state match {
      case Errored(lcssNext) => {
        val lcssBase =
          lcssNext.getOrElse(Database.data.channels.get(peerId).get.lcss)

        val lcssOverride = lcssBase
          .copy(
            localBalanceMsat = newLocalBalance,
            remoteBalanceMsat =
              lcssBase.initHostedChannel.channelCapacityMsat - newLocalBalance,
            incomingHtlcs = Nil,
            outgoingHtlcs = Nil,
            localUpdates = lcssBase.localUpdates + 1,
            remoteUpdates = lcssBase.remoteUpdates + 1,
            blockDay = Main.currentBlockDay,
            remoteSigOfLocal = ByteVector64.Zeroes
          )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        state = Overriding(lcssOverride)

        sendMessage(lcssOverride.stateOverride)
          .map((v: ujson.Value) => v("status").str)
      }
      case _ => {
        Future { s"can't send to this channel since it is not active." }
      }
    }
  }
}
