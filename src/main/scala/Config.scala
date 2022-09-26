import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import upickle.default._
import scodec.bits.ByteVector
import scoin._
import scoin.ln.Color
import scoin.hc.{InitHostedChannel, HostedChannelBranding}

object Config {
  import Picklers.given

  def fromFile(basePath: Path): Try[Config] =
    Try(read[Config](basePath.resolve("config.json")))
      .map(_.copy(basePath = Some(basePath)))

  def defaults: Config = Config()
}

case class Config(
    // path
    basePath: Option[Path] = None,

    // settings
    isDev: Boolean = true,

    // channels settings
    cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(137),
    feeBase: MilliSatoshi = MilliSatoshi(1000L),
    feeProportionalMillionths: Long = 1000L,
    maxHtlcValueInFlightMsat: Long = 100000000L,
    htlcMinimumMsat: MilliSatoshi = MilliSatoshi(1000L),
    maxAcceptedHtlcs: Int = 12,
    channelCapacityMsat: MilliSatoshi = MilliSatoshi(100000000L),
    initialClientBalanceMsat: MilliSatoshi = MilliSatoshi(0),

    // branding
    contactURL: String = "",
    logoFile: String = "",
    hexColor: String = "#ffffff",

    // extra
    requireSecret: Boolean = false,
    permanentSecrets: List[String] = List.empty
) {
  def init: InitHostedChannel = InitHostedChannel(
    maxHtlcValueInFlightMsat = UInt64(maxHtlcValueInFlightMsat),
    htlcMinimumMsat = htlcMinimumMsat,
    maxAcceptedHtlcs = maxAcceptedHtlcs,
    channelCapacityMsat = channelCapacityMsat,
    initialClientBalanceMsat = initialClientBalanceMsat
  )

  def branding(logger: nlog.Logger): Option[HostedChannelBranding] =
    if (contactURL == "") None
    else {
      val optionalPng =
        Try {
          val png = ByteVector.view(
            Files.readAllBytes(basePath.get.resolve(logoFile))
          )

          if (png.size > 65535) {
            logger.warn.msg(
              s"logoFile must be a PNG with at most 65535 bytes, but $logoFile has ${png.size}."
            )
            throw new java.lang.IllegalArgumentException("")
          }

          png
        }.toOption

      val color: Color = Try {
        val rgb = ByteVector.fromValidHex(hexColor.drop(1))
        Color(rgb(0), rgb(1), rgb(2))
      }.getOrElse(Color(255.toByte, 255.toByte, 255.toByte))

      Some(
        HostedChannelBranding(
          color,
          optionalPng,
          contactURL
        )
      )
    }

  override def toString(): String = {
    val chan =
      s"capacity=$channelCapacityMsat initial-client-balance=$initialClientBalanceMsat"
    val policy = {
      val proportional =
        f"${(feeProportionalMillionths.toDouble * 100 / 1000000)}%.2f"
      s"fees=$feeBase/$proportional% min-delay=${cltvExpiryDelta.toInt}"
    }
    val htlc =
      s"max-htlcs=$maxAcceptedHtlcs max-htlc-sum=${maxHtlcValueInFlightMsat}msat min-htlc=$htlcMinimumMsat"
    val branding =
      if (contactURL != "")
        s"contact=$contactURL color=$hexColor logo=$logoFile"
      else "~"

    s"channel($chan) policy($policy) branding($branding) htlc($htlc)"
  }
}
