package httpserver

import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.{ActorRef, ActorSystem, DispatcherSelector}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import httpserver.CurrencyConverterService.{ConvertCurrencyToEUR, CurrencyRateMessageResponse}
import httpserver.conversion.messages.JsonFormats._
import httpserver.conversion.messages._
import org.slf4j.{Logger, LoggerFactory}

import java.time.Duration
import java.util.UUID.randomUUID
import scala.language.postfixOps

class Routes(cashService: ActorRef[CashService.Command])(implicit val system: ActorSystem[_]) {

  val log: Logger = LoggerFactory.getLogger("Route parser")
  private implicit val timeout: Timeout = Timeout.create(Duration.ofSeconds(3))

  private val appKey = system.settings.config.getString("CurrencyConverterServiceConfig.appKey")

  private def requestToResponse[A <: CurrencyRateMessageDTO](currencyRateMessageDTO: CurrencyRateMessageDTO): Route = {
    val currencyConverterService = system.systemActorOf(
      CurrencyConverterService(appKey, cashService), randomUUID().toString, DispatcherSelector.fromConfig("working-dispatcher"))
    val currencyRateMessage = currencyRateMessageDTO.toCurrencyRateMessage
    onSuccess(currencyConverterService.ask(ConvertCurrencyToEUR(currencyRateMessage, _))) {
      messageResponse: CurrencyRateMessageResponse =>
        log.debug(s"messageResponse: $messageResponse")
        messageResponse.response match {
          case Left(e) =>
            log.error(s"$e")
            complete(InternalServerError)
          case Right(responseValue) =>
            responseValue match {
              case mV1: CurrencyRateMessageV1 => complete((StatusCodes.OK, mV1.toCurrencyRateMessageDTO))
              case mV2: CurrencyRateMessageV2 => complete((StatusCodes.OK, mV2.toCurrencyRateMessageDTO))
            }
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
