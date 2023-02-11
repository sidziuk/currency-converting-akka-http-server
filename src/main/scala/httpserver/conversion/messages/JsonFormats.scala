package httpserver.conversion.messages

import spray.json.{DefaultJsonProtocol, RootJsonFormat}

object JsonFormats {

  import DefaultJsonProtocol._

  implicit val currencyRateMassageV1JsonFormat: RootJsonFormat[CurrencyRateMessageV1DTO] = jsonFormat6(CurrencyRateMessageV1DTO)

  implicit val currencyRateMassageV2JsonFormat: RootJsonFormat[CurrencyRateMessageV2DTO] = jsonFormat4(CurrencyRateMessageV2DTO)


}
