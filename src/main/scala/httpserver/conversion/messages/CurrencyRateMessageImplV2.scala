package httpserver.conversion.messages

import httpserver.CurrencyConverterService.{CurrencyRateMessageResponse, ErrorMessage}

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode

trait CurrencyRateMessageV2Trait {

  def odds: Double

  def stake: BigDecimal

  def currency: String

  def date: Any
}

final case class CurrencyRateMessageV2DTO(
                                           odds: Double,
                                           stake: BigDecimal,
                                           currency: String,
                                           override val date: String
                                         ) extends CurrencyRateMessageV2Trait with CurrencyRateMessageDTO {
  def toCurrencyRateMessage: CurrencyRateMessageV2 = CurrencyRateMessageV2(
    odds = odds,
    stake = stake,
    currency = currency,
    date = Instant.parse(date)
  )
}

final case class CurrencyRateMessageV2(
                                        odds: Double,
                                        stake: BigDecimal,
                                        currency: String,
                                        override val date: Instant
                                      ) extends CurrencyRateMessageV2Trait with CurrencyRateMessage {

  def toCurrencyRateMessageDTO: CurrencyRateMessageV2DTO = CurrencyRateMessageV2DTO(
    odds = odds,
    stake = stake,
    currency = currency,
    date = date.toString
  )

  override def getLocalDate: LocalDate = date.atZone(ZoneOffset.UTC).toLocalDate

  override def getResponse(
                            getCurrencyRate: (String, LocalDate) => Future[Either[ErrorMessage, BigDecimal]]
                          )(implicit ec: ExecutionContext): Future[CurrencyRateMessageResponse] = {
    val convertingCurrencyName = this.currency
    val convertingCurrencyAmount = this.stake
    val localDate = this.getLocalDate
    val currencyRate = getCurrencyRate(convertingCurrencyName, localDate)
    val response: Future[CurrencyRateMessageResponse] = currencyRate.map {
      case Left(e) => CurrencyRateMessageResponse(Left(e))
      case Right(newCurrencyRate) => CurrencyRateMessageResponse(
        Right(
          this.copy(
            currency = "EUR",
            stake = (convertingCurrencyAmount / newCurrencyRate).setScale(5, RoundingMode.HALF_DOWN)
          )
        )
      )
    }
    response
  }
}

