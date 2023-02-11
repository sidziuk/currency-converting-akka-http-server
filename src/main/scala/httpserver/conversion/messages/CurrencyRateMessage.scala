package httpserver.conversion.messages

import httpserver.CurrencyConverterService.{CashedCurrencyConversionRate, CurrencyRateMessageResponse, ErrorMessage}

import java.time.LocalDate

trait CurrencyRateMessage {
  def toCurrencyRateMessageDTO: CurrencyRateMessageDTO

  def getLocalDate: LocalDate

  def getResponseAndCash(
                          getCurrencyRate: (String, LocalDate) =>
                            (Either[ErrorMessage, BigDecimal], CashedCurrencyConversionRate)
                        ): (CurrencyRateMessageResponse, CashedCurrencyConversionRate)
}

trait CurrencyRateMessageDTO {
  def toCurrencyRateMessage: CurrencyRateMessage
}
