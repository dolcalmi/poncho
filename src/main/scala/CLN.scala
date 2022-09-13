import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.util.{Try, Success, Failure}
import scala.util.control.Breaks._
import scala.util.chaining._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.collection.mutable.ArrayBuffer
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.{Poll, Timer}
import scala.util.{Failure, Success}
import ujson._
import scodec.bits.{ByteVector, BitVector}
import scodec.codecs.uint16
import unixsocket.UnixSocket
import scoin._
import scoin.ln._
import scoin.hc._
import scoin.hc.HostedChannelCodecs._
import scoin.Crypto.{PublicKey, PrivateKey}

class CLN(master: ChannelMaster) extends NodeInterface {
  import Picklers.given

  private var initCallback = () => {}
  private var rpcAddr: String = ""
  private var hsmSecret: Path = Paths.get("")
  private var nextId = 0
  private var onStartup = true

  Timer.timeout(10.seconds) { () => onStartup = false }

  def rpc(
      method: String,
      params: ujson.Obj = ujson.Obj()
  ): Future[ujson.Value] = {
    if (rpcAddr == "") {
      return Future.failed(PonchoException("rpc address is not known yet"))
    }

    nextId += 1

    val payload =
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> nextId,
          "method" -> method,
          "params" -> params
        )
      )

    UnixSocket
      .call(rpcAddr, payload)
      .future
      .map(ujson.read(_))
      .flatMap(read =>
        if (read.obj.contains("error")) {
          Future.failed(PonchoException(read("error")("message").str))
        } else {
          Future.successful(read("result"))
        }
      )
  }

  def answer(req: ujson.Value)(result: ujson.Value): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "result" -> result
        )
      )
    )
  }

  def answer(req: ujson.Value)(errorMessage: String): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "error" -> ujson.Obj(
            "message" -> errorMessage
          )
        )
      )
    )
  }

  lazy val privateKey: PrivateKey = {
    val salt = Array[UByte](0.toByte.toUByte)
    val info = "nodeid".getBytes().map(_.toUByte)
    val secret = Files.readAllBytes(hsmSecret).map(_.toUByte)

    val sk = hkdf256.hkdf(salt, secret, info, 32)
    PrivateKey(ByteVector32(ByteVector(sk.map(_.toByte))))
  }

  lazy val publicKey: PublicKey = privateKey.publicKey

  def getChainHash(): Future[ByteVector32] =
    rpc("getinfo", ujson.Obj())
      .map(_("network").str)
      .map({
        case "bitcoin" => Block.LivenetGenesisBlock.hash.toHex
        case "testnet" => Block.TestnetGenesisBlock.hash.toHex
        case "signet"  => Block.SignetGenesisBlock.hash.toHex
        case "regtest" => Block.RegtestGenesisBlock.hash.toHex
        case chain =>
          throw IllegalArgumentException(s"unknown chain name '$chain'")
      })
      .map(ByteVector32.fromValidHex(_))

  def getAddress(): Future[String] =
    rpc("newaddr").map(info => info("bech32").str)

  def getCurrentBlock(): Future[BlockHeight] =
    rpc("getchaininfo").map(info => BlockHeight(info("headercount").num.toLong))

  def getBlockByHeight(height: BlockHeight): Future[Block] =
    rpc("getrawblockbyheight", ujson.Obj("height" -> height.toInt))
      .flatMap(resp =>
        resp("block").str
          .pipe(hex => Future(Block.read(hex)))
      )

  def inspectOutgoingPayment(
      identifier: HtlcIdentifier,
      paymentHash: ByteVector32
  ): Future[PaymentStatus] =
    rpc("listsendpays", ujson.Obj("payment_hash" -> paymentHash.toHex))
      .map(response =>
        response("payments").arr
          .filter(_.obj.contains("label"))
          .filter(
            // use a filter because there may be multiple sendpays with the same hash and label
            p =>
              Try(
                (identifier.scid.toString, identifier.id.toLong) ==
                  upickle.default.read[(String, Long)](p("label").str)
              ).getOrElse(false)
          )
          .pipe(toStatus(_))
      )

  private def toStatus(results: ArrayBuffer[ujson.Value]): PaymentStatus =
    if (results.size == 0)
      // no outgoing payments found, this means the payment was never attempted
      Some(Left(None))
    else {
      // we have at least one match
      if (results.exists(res => res("status").str == "complete"))
        // if at least one result is complete then this is indeed fully complete
        Some(
          Right(
            ByteVector32(
              ByteVector.fromValidHex(
                results
                  .find(res => res("status").str == "complete")
                  .get("payment_preimage")
                  .str
              )
            )
          )
        )
      else if (results.exists(res => res("status").str == "pending"))
        // if at least one result is complete then this is still pending
        None
      else if (results.forall(res => res("status").str == "failed"))
        // but if all are failed then we consider it failed
        Some(
          Left(
            results.last // take the last and use its error
              .obj
              .pipe(o => o.get("onionreply").orElse(o.get("erroronion")))
              .map(_.str)
              .map(ByteVector.fromValidHex(_))
              .map(FailureOnion(_))
          )
        )
      else None // we don't know
    }

  def sendCustomMessage(
      peerId: ByteVector,
      message: LightningMessage
  ): Future[ujson.Value] = {
    val result = hostedMessageCodec.encode(message).toOption.get.toByteVector
    val tagHex = result.take(2).toHex
    val value = result.drop(2)
    val lengthHex = uint16
      .encode(value.size.toInt)
      .toOption
      .get
      .toByteVector
      .toHex
    val payload = tagHex ++ lengthHex ++ value.toHex

    master.log(s"  ::> sending $message --> ${peerId.toHex}")
    rpc(
      "sendcustommsg",
      ujson.Obj(
        "node_id" -> peerId.toHex,
        "msg" -> payload
      )
    )
  }

  def sendOnion(
      chan: Channel,
      htlcId: Long,
      paymentHash: ByteVector32,
      firstHop: ShortChannelId,
      amount: MilliSatoshi,
      cltvExpiryDelta: CltvExpiryDelta,
      onion: ByteVector
  ): Unit = {
    rpc("listchannels", ujson.Obj("short_channel_id" -> firstHop.toString))
      .map(resp =>
        resp("channels").arr.headOption.flatMap(chan =>
          List(chan("source").str, chan("destination").str)
            .map(ByteVector.fromValidHex(_))
            .find(id => id != master.node.publicKey)
        )
      )
      .onComplete {
        case Failure(err) => {
          master.log(s"failed to get peer for channel: $err")
          chan
            .gotPaymentResult(
              htlcId,
              Some(
                Left(
                  Some(NormalFailureMessage(UnknownNextPeer))
                )
              )
            )
        }
        case Success(None) => {
          master.log("didn't find peer for channel")
          chan.gotPaymentResult(
            htlcId,
            Some(
              Left(
                Some(NormalFailureMessage(UnknownNextPeer))
              )
            )
          )
        }
        case Success(Some(targetPeerId: ByteVector)) =>
          rpc(
            "sendonion",
            ujson.Obj(
              "first_hop" -> ujson.Obj(
                "id" -> targetPeerId.toHex,
                "amount_msat" -> amount.toLong,
                "delay" -> cltvExpiryDelta.toInt
              ),
              "onion" -> onion.toHex,
              "payment_hash" -> paymentHash.toHex,
              "label" -> upickle.default
                .write((chan.shortChannelId.toString, htlcId))
            )
          )
            .onComplete {
              case Failure(e) => {
                master.log(s"sendonion failure: $e")
                chan.gotPaymentResult(
                  htlcId,
                  Some(Left(None))
                )
              }
              case Success(_) => {}
            }
      }
  }

  def handleRPC(line: String): Unit = {
    val req = ujson.read(line)
    val params = req("params")
    def reply(result: ujson.Value) = answer(req)(result)
    def replyError(err: String) = answer(req)(err)

    req("method").str match {
      case "getmanifest" =>
        reply(
          ujson.Obj(
            "dynamic" -> false, // custom features can only be set on non-dynamic
            "options" -> ujson.Arr(),
            "subscriptions" -> ujson.Arr(
              "sendpay_success",
              "sendpay_failure",
              "connect",
              "disconnect"
            ),
            "hooks" -> ujson.Arr(
              ujson.Obj("name" -> "custommsg"),
              ujson.Obj("name" -> "htlc_accepted")
            ),
            "rpcmethods" -> ujson.Arr(
              ujson.Obj(
                "name" -> "parse-lcss",
                "usage" -> "peerid last_cross_signed_state_hex",
                "description" -> "Parse a hex representation of a last_cross_signed_state as provided by a mobile client."
              ),
              ujson.Obj(
                "name" -> "add-hc-secret",
                "usage" -> "secret",
                "description" -> ("Adds a {secret} (hex, 32 bytes) to the list of acceptable secrets for when a client invokes a hosted channel. " +
                  "This secret can only be used once. You can add the same secret multiple times so it can be used multiple times. " +
                  "You can also add permanent secrets on the config file.")
              ),
              ujson.Obj(
                "name" -> "remove-hc-secret",
                "usage" -> "secret",
                "description" -> "Removes a {secret} (hex, 32 bytes) to the list of acceptable secrets for when a client invokes a hosted channel. See also `add-hc-secret`."
              ),
              ujson.Obj(
                "name" -> "hc-list",
                "usage" -> "",
                "description" -> "Lists all your hosted channels."
              ),
              ujson.Obj(
                "name" -> "hc-channel",
                "usage" -> "peerid",
                "description" -> "Shows your hosted channel with {peerid}."
              ),
              ujson.Obj(
                "name" -> "hc-override",
                "usage" -> "peerid msatoshi",
                "description" -> "Proposes overriding the state of the channel with {peerid} with the next local balance being equal to {msatoshi}."
              ),
              ujson.Obj(
                "name" -> "hc-request-channel",
                "usage" -> "peerid",
                "description" -> "Requests a hosted channel from another hosted channel provider (do not use)."
              )
            ),
            "notifications" -> ujson.Arr(),
            "featurebits" -> ujson.Obj(
              "init" -> Utils.generateFeatureBits(Set(32973, 257)),
              "node" -> Utils.generateFeatureBits(Set(257))
              // "channel" -> Utils.generateFeatureBits(Set(32975))
            )
          )
        )
      case "init" => {
        reply(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> req("id").num,
            "result" -> ujson.Obj()
          )
        )

        val lightningDir = params("configuration")("lightning-dir").str
        rpcAddr = lightningDir + "/" + params("configuration")("rpc-file").str
        hsmSecret = Paths.get(lightningDir + "/hsm_secret")

        initCallback()
      }
      case "custommsg" => {
        reply(ujson.Obj("result" -> "continue"))

        val peerId = ByteVector.fromValidHex(params("peer_id").str)
        val body = params("payload").str
        val payload: ByteVector = ByteVector.fromValidHex(body)

        hostedMessageCodec
          .decode(
            ByteVector
              .concat(
                List(
                  payload.take(2),
                  payload.drop(2 /* tag */ + 2 /* length */ )
                )
              )
              .toBitVector
          )
          .toTry match {
          case Success(msg) =>
            master.getChannel(peerId).gotPeerMessage(msg.value)
          case Failure(err) =>
            master.log(s"failed to parse client messages: $err")
        }
      }
      case "htlc_accepted" => {
        // we wait here because on startup c-lightning will replay all pending htlcs
        // and at that point we won't have the hosted channels active with our clients yet
        Timer.timeout(
          if (onStartup) 3.seconds
          else 0.seconds
        )(() => {
          val htlc = params("htlc")
          val onion = params("onion")

          // if we're the final hop of an htlc this property won't exist
          if (!onion.obj.contains("short_channel_id")) {
            // just continue so our node will accept this payment
            reply(ujson.Obj("result" -> "continue"))
          } else {
            val hash = ByteVector32.fromValidHex(htlc("payment_hash").str)
            val sourceChannel = ShortChannelId(htlc("short_channel_id").str)
            val sourceAmount = MilliSatoshi(
              htlc.obj.get("amount_msat").orElse(htlc.obj.get("amount")) match {
                case Some(ujson.Num(num)) => num.toLong
                case Some(ujson.Str(str)) => str.takeWhile(_.isDigit).toLong
                case what =>
                  throw new Exception(
                    s"unexpected htlc.amount at htlc_accepted hook: $what"
                  )
              }
            )
            val sourceId = htlc("id").num.toLong
            val cltvIn = CltvExpiry(BlockHeight(htlc("cltv_expiry").num.toLong))

            val targetChannel = ShortChannelId(onion("short_channel_id").str)
            val targetAmount = MilliSatoshi(
              onion.obj
                .get("forward_msat")
                .orElse(htlc.obj.get("forward_amount")) match {
                case Some(ujson.Num(num)) => num.toLong
                case Some(ujson.Str(str)) => str.takeWhile(_.isDigit).toLong
                case what =>
                  throw new Exception(
                    s"unexpected onion.forward_amount at htlc_accepted hook: $what"
                  )
              }
            )
            val cltvOut =
              CltvExpiry(BlockHeight(onion("outgoing_cltv_value").num.toLong))
            val nextOnion = ByteVector.fromValidHex(onion("next_onion").str)
            val sharedSecret =
              ByteVector32.fromValidHex(onion("shared_secret").str)

            master.database.data.channels.find((peerId, chandata) =>
              HostedChannelHelpers.getShortChannelId(
                publicKey.value,
                peerId
              ) == targetChannel
            ) match {
              case Some((peerId, _)) => {
                master
                  .getChannel(peerId)
                  .addHtlc(
                    htlcIn = HtlcIdentifier(sourceChannel, sourceId),
                    paymentHash = hash,
                    amountIn = sourceAmount,
                    amountOut = targetAmount,
                    cltvIn = cltvIn,
                    cltvOut = cltvOut,
                    nextOnion = nextOnion
                  )
                  .foreach { status =>
                    val response = status match {
                      case Some(Right(preimage)) =>
                        ujson.Obj(
                          "result" -> "resolve",
                          "payment_key" -> preimage.toHex
                        )
                      case Some(Left(Some(FailureOnion(onion)))) =>
                        // must unwrap the onion here because the hosted channel
                        // won't unwrap whatever packet they got from the hosted peer
                        ujson.Obj(
                          "result" -> "fail",
                          "failure_onion" -> Sphinx.FailurePacket
                            .wrap(onion, sharedSecret)
                            .toHex
                        )
                      case Some(Left(Some(NormalFailureMessage(message)))) =>
                        ujson.Obj(
                          "result" -> "fail",
                          "failure_message" -> message.toHex
                        )
                      case Some(Left(None)) =>
                        ujson
                          .Obj("result" -> "fail", "failure_message" -> "1007")
                      case None =>
                        ujson.Obj("result" -> "continue")
                    }
                    reply(response)
                  }
              }
              case None => {
                reply(ujson.Obj("result" -> "continue"))
              }
            }
          }
        })
      }
      case "sendpay_success" => {
        val successdata = params("sendpay_success")
        if (successdata.obj.contains("label"))
          for {
            label <- successdata("label").strOpt
            (scidStr, htlcId) <- Try(
              upickle.default.read[(String, Long)](label)
            ).toOption
            scid = ShortChannelId(scidStr)
            (peerId, _) <- master.database.data.channels.find((p, _) =>
              HostedChannelHelpers.getShortChannelId(publicKey.value, p) == scid
            )
          } yield master
            .getChannel(peerId)
            .gotPaymentResult(
              htlcId,
              toStatus(ArrayBuffer(successdata))
            )
      }
      case "sendpay_failure" => {
        val failuredata = params("sendpay_failure")("data")
        if (failuredata.obj.contains("label"))
          for {
            label <- failuredata("label").strOpt
            (scidStr, htlcId) <- Try(
              upickle.default.read[(String, Long)](label)
            ).toOption
            scid = ShortChannelId(scidStr)
            (peerId, _) <- master.database.data.channels.find((p, _) =>
              HostedChannelHelpers.getShortChannelId(publicKey.value, p) == scid
            )
            channel = master.getChannel(peerId)
          } yield {
            failuredata("status").str match {
              case "pending" =>
                Timer.timeout(1.second) { () =>
                  inspectOutgoingPayment(
                    HtlcIdentifier(scid, htlcId),
                    ByteVector32.fromValidHex(failuredata("payment_hash").str)
                  ).foreach { result =>
                    channel.gotPaymentResult(htlcId, result)
                  }
                }
              case "failed" =>
                channel.gotPaymentResult(
                  htlcId,
                  toStatus(ArrayBuffer(failuredata))
                )
            }
          }
      }
      case "connect" => {
        // val id = params("id").str
        // val address = params("address")("address").str
        // master.log(s"$id connected: $address")
        // TODO: send InvokeHostedChannel to all hosted peers from which we are clients
        //       and related flows -- for example sending LastCrossSignedState etc
      }
      case "disconnect" => {
        // val id = params("id").str
        // master.log(s"$id disconnected")
      }

      // custom rpc methods
      case "parse-lcss" => {
        (for {
          peerIdHex <- params match {
            case o: ujson.Obj =>
              o.value.get("peerid").flatMap(_.strOpt)
            case a: ujson.Arr => a.value.headOption.flatMap(_.strOpt)
            case _            => None
          }
          peerId <- ByteVector.fromHex(peerIdHex)
          peer = PublicKey(peerId)
          lcssHex <- params match {
            case o: ujson.Obj =>
              o.value.get("last_cross_signed_state_hex").flatMap(_.strOpt)
            case a: ujson.Arr => a.value.drop(1).headOption.flatMap(_.strOpt)
            case _            => None
          }
          lcssBits <- BitVector.fromHex(lcssHex)
          decoded <- lastCrossSignedStateCodec.decode(lcssBits).toOption
          lcss = decoded.value
        } yield (peer, lcss)) match {
          case Some((peer, lcss)) if lcss.verifyRemoteSig(peer) =>
            if (
              Crypto.verifySignature(
                lcss.reverse.hostedSigHash,
                lcss.localSigOfRemote,
                publicKey
              )
            )
              upickle.default.write(lcss).pipe(j => reply(ujson.read(j)))
            else
              replyError("provided lcss wasn't signed by us")
          case None =>
            replyError("failed to decode last_cross_signed_state or peerid")
          case _ =>
            replyError("provided lcss signature does not match the peerid")
        }
      }

      case "add-hc-secret" =>
        if (!master.config.requireSecret) {
          replyError(
            "`requireSecret` must be set to true on config.json for this to do anything."
          )
        } else
          params match {
            case o: ujson.Obj => o.value.get("secret").flatMap(_.strOpt)
            case a: ujson.Arr => a.value.headOption.flatMap(_.strOpt)
            case _            => None
          } match {
            case Some(secret) => {
              master.temporarySecrets = master.temporarySecrets :+ secret
              reply(ujson.Obj("added" -> true))
            }
            case None => replyError("secret not given")
          }

      case "remove-hc-secret" =>
        if (!master.config.requireSecret) {
          replyError(
            "`requireSecret` must be set to true on config.json for this to do anything."
          )
        } else
          params match {
            case o: ujson.Obj => o.value.get("secret").flatMap(_.strOpt)
            case a: ujson.Arr => a.value.headOption.flatMap(_.strOpt)
            case _            => None
          } match {
            case Some(secret) => {
              master.temporarySecrets =
                master.temporarySecrets.filterNot(_ == secret)
              reply(ujson.Obj("removed" -> true))
            }
            case None => replyError("secret not given")
          }

      case "hc-list" =>
        reply(master.database.data.channels.toList.map(master.channelJSON))

      case "hc-channel" =>
        (for {
          peerHex <- params match {
            case o: ujson.Obj => o.value.get("peerId").flatMap(_.strOpt)
            case a: ujson.Arr => a.value.headOption.flatMap(_.strOpt)
            case _            => None
          }
          peerId <- ByteVector.fromHex(peerHex)
          data <- master.database.data.channels.get(peerId)
        } yield reply(
          master.channelJSON((peerId, data))
        )) getOrElse replyError("couldn't find that channel")

      case "hc-override" =>
        (params match {
          case _: ujson.Obj =>
            Some((params("peerid").strOpt, params("msatoshi").numOpt))
          case arr: ujson.Arr if arr.value.size == 2 =>
            Some((params(0).strOpt, params(1).numOpt))
          case _ => None
        }) match {
          case Some(Some(peerId), Some(msatoshi)) => {
            master
              .getChannel(ByteVector.fromValidHex(peerId))
              .proposeOverride(MilliSatoshi(msatoshi.toLong))
              .onComplete {
                case Success(msg) => reply(msg)
                case Failure(err) => replyError(err.toString)
              }
          }
          case _ => {
            replyError("invalid parameters")
          }
        }

      case "hc-request-channel" =>
        (params match {
          case _: ujson.Obj =>
            Some(params("peerid").strOpt)
          case arr: ujson.Arr if arr.value.size == 1 =>
            Some(params(0).strOpt)
          case _ => None
        }) match {
          case Some(Some(peerId)) => {
            master
              .getChannel(ByteVector.fromValidHex(peerId))
              .requestHostedChannel()
              .onComplete {
                case Success(msg) => reply(msg)
                case Failure(err) => replyError(err.toString)
              }
          }
          case _ => {
            replyError("invalid parameters")
          }
        }
    }
  }

  def main(onInit: () => Unit): Unit = {
    initCallback = onInit

    Poll(0).startRead { _ =>
      breakable {
        var current = Array.empty[Byte]

        while (true) {
          Try(scala.Console.in.read()) match {
            case Success(char) if char == 10 =>
              // newline, we've got a full line, so handle it
              val line = new String(current, StandardCharsets.UTF_8).trim()
              if (line.size > 0) handleRPC(line)
              current = Array.empty
            case Success(char) =>
              // normal char, add it to the current
              current = current :+ char.toByte
            case Failure(err) =>
              // EOF
              break()
          }
        }
      }
    }
  }
}
