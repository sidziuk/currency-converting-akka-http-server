package httpserver

import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.util.Timeout
import httpserver.CashService.{GetCashedCurrencyRate, SetNewValue}
import httpserver.conversion.messages.CurrencyRateMessage
import org.slf4j.{Logger, LoggerFactory}
import spray.json._

import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDate}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.math.BigDecimal.RoundingMode
import scala.util.{Failure, Success}

object CurrencyConverterService extends DefaultJsonProtocol {

  type CashedCurrencyConversionRate = Map[String, BigDecimal]

  val log: Logger = LoggerFactory.getLogger("CurrencyConverterService")

  implicit val timeout: Timeout = Timeout.create(Duration.ofSeconds(3))

  sealed trait Command

  final case class ConvertCurrencyToEUR(currencyRateMassage: CurrencyRateMessage, replyTo: ActorRef[CurrencyRateMessageResponse]) extends Command

  final case class SendResponse(currencyRateMessageResponse: CurrencyRateMessageResponse, replyTo: ActorRef[CurrencyRateMessageResponse]) extends Command

  sealed trait Response

  final case class CurrencyRateMessageResponse(response: Either[ErrorMessage, CurrencyRateMessage]) extends Response

  final case class ErrorMessage(value: String)

  def apply(appKey: String, cashService: ActorRef[CashService.Command]): Behavior[Command] = {

    def registry: Behavior[Command] = {
      Behaviors.receive { (context, message) =>
        implicit val system: ActorSystem[Nothing] = context.system
        implicit val executionContext: ExecutionContextExecutor = system.executionContext

        def getCurrencyRateFromHttpRequest(currencyName: String, request: HttpRequest): Future[Either[ErrorMessage, BigDecimal]] = {
          Http().singleRequest(request)
            .flatMap {
              case HttpResponse(StatusCodes.OK, _, entity, _) =>
                Unmarshal(entity).to[String].map { entity =>
                  val currencyRate = entity
                    .parseJson
                    .asJsObject
                    .getFields("rates")
                    .head
                    .asJsObject.getFields(s"$currencyName")
                    .head
                    .toString
                  Right(BigDecimal(currencyRate).setScale(5, RoundingMode.HALF_DOWN))
                }
              case _ => Future(Left(ErrorMessage("Error: source service")))
            }
        }

        def getCurrencyRateFromExchangerateHost(currencyName: String, date: LocalDate): Future[Either[ErrorMessage, BigDecimal]] = {
          val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
          val formattedDay = date.format(dateFormat)
          val request = Get(s"https://api.exchangerate.host/$formattedDay?base=EUR&symbols=$currencyName&places=5")
          getCurrencyRateFromHttpRequest(currencyName, request)
        }

        def getCurrencyRateFromFreecurrencyapi(currencyName: String, date: LocalDate): Future[Either[ErrorMessage, BigDecimal]] = {
          val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
          val formattedDay = date.format(dateFormat)
          val request = Get(s"https://api.freecurrencyapi.com/v1/historical?apikey=$appKey&date_from=$formattedDay&date_to=$formattedDay&base_currency=EUR&currencies=$currencyName")
          getCurrencyRateFromHttpRequest(currencyName, request)
        }

        def getCurrencyRate(currencyName: String, date: LocalDate): Future[Either[ErrorMessage, BigDecimal]] = {

          def startCashTimerAndReturnNewCurrencyAndNewCash(
                                                            cashKey: String,
                                                            newCurrencyRate: BigDecimal
                                                          ): Future[Either[ErrorMessage, BigDecimal]] = {
            cashService ! SetNewValue(cashKey, newCurrencyRate)
            Future(Right(newCurrencyRate))
          }

          val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
          val formattedDay = date.format(dateFormat)
          val cashKey = s"$currencyName$formattedDay"

          cashService.ask(GetCashedCurrencyRate(cashKey: String, _)).flatMap { cashedCurrencyRate =>
            if (cashedCurrencyRate.cashedCurrencyRate.isDefined) {
              log.info(s"Getting currency value from cash")
              Future(Right(cashedCurrencyRate.cashedCurrencyRate.get))
            } else {
              val newMayBeCurrencyRateFrom1Source = getCurrencyRateFromExchangerateHost(currencyName, date)
              newMayBeCurrencyRateFrom1Source.flatMap {
                case Left(e1) =>
                  log.info(s"First currency rate source error: $e1")
                  val newMayBeCurrencyRateFrom2Source = getCurrencyRateFromFreecurrencyapi(currencyName, date)
                  newMayBeCurrencyRateFrom2Source.flatMap {
                    case Left(e2) =>
                      Future(Left(ErrorMessage(s"Error from first source: $e1, Error from second source: $e2")))
                    case Right(newCurrencyRate2) =>
                      startCashTimerAndReturnNewCurrencyAndNewCash(cashKey, newCurrencyRate2)
                  }
                case Right(newCurrencyRate1) =>
                  startCashTimerAndReturnNewCurrencyAndNewCash(cashKey, newCurrencyRate1)
              }
            }
          }
        }

        message match {
          case ConvertCurrencyToEUR(currencyRateMassage, replyTo) =>
            log.info(s"I have got message for conversion: $currencyRateMassage")
            val response = currencyRateMassage.getResponse(getCurrencyRate)
            context.pipeToSelf(response) {
              case Success(currencyRateMessageResponse) =>
                SendResponse(currencyRateMessageResponse, replyTo)
              case Failure(ex) =>
                SendResponse(CurrencyRateMessageResponse(Left(ErrorMessage(s"Error: $ex"))), replyTo)
            }
            log.info(s"Conversion response: $response")
            Behaviors.same
          case SendResponse(currencyRateMessageResponse, replyTo) =>
            replyTo ! currencyRateMessageResponse
            Behaviors.stopped
        }
      }
    }

    registry
  }
}
