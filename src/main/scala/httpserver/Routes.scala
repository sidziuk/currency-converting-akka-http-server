package httpserver

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.util.Timeout
import httpserver.CurrencyConverterService.{ConvertCurrencyToEUR, CurrencyRateMessageResponse}
import org.slf4j.{Logger, LoggerFactory}
import httpserver.conversion.messages.JsonFormats._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import httpserver.conversion.messages.{CurrencyRateMessageDTO, CurrencyRateMessageV1DTO, CurrencyRateMessageV2DTO}

import java.time.Duration
import scala.language.postfixOps

class Routes(currencyConverterService: ActorRef[CurrencyConverterService.Command])(implicit val system: ActorSystem[_]) {

  val log: Logger = LoggerFactory.getLogger("Route parser")
  private implicit val timeout: Timeout = Timeout.create(Duration.ofSeconds(3))

  private def requestToResponse[T](currencyRateMessageDTO: CurrencyRateMessageDTO)(implicit m: ToEntityMarshaller[T]): Route = {
    val currencyRateMessage = currencyRateMessageDTO.toCurrencyRateMessage
    onSuccess(currencyConverterService.ask(ConvertCurrencyToEUR(currencyRateMessage, _))) {
      messageResponse: CurrencyRateMessageResponse =>
        messageResponse.response match {
          case Left(e) =>
            log.error(s"$e")
            complete(InternalServerError)
          case Right(responseValue) =>
            val response = responseValue
              .toCurrencyRateMessageDTO
              .asInstanceOf[T]
            complete((StatusCodes.OK, response))
        }
    }
  }

  private val conversionRoteV1: Route = concat(
    pathPrefix("conversion") {
      concat(
        path("trade") {
          concat(
            post {
              concat(
                entity(as[CurrencyRateMessageV1DTO]) { currencyRateMessageV1DTO =>
                  requestToResponse[CurrencyRateMessageV1DTO](currencyRateMessageV1DTO)
                },
                entity(as[CurrencyRateMessageV2DTO]) { currencyRateMessageV2DTO =>
                  requestToResponse[CurrencyRateMessageV2DTO](currencyRateMessageV2DTO)
                }
              )
            }
          )
        }
      )
    }
  )

  val baseConversionRote: Route =
    pathPrefix("api") {
      concat(
        pathPrefix("v1") {
          concat(
            conversionRoteV1
          )
        }
      )
    }
}
