package httpserver.conversion.messages

import httpserver.CurrencyConverterService.{CurrencyRateMessageResponse, ErrorMessage}

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

trait CurrencyRateMessage {

  def toCurrencyRateMessageDTO: CurrencyRateMessageDTO

  def getLocalDate: LocalDate

  def getResponse(
                   getCurrencyRate: (String, LocalDate) => Future[Either[ErrorMessage, BigDecimal]]
                 )(implicit ec: ExecutionContext): Future[CurrencyRateMessageResponse]
}

trait CurrencyRateMessageDTO {
  def toCurrencyRateMessage: CurrencyRateMessage
}