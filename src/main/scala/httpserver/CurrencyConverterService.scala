package httpserver

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import spray.json.DefaultJsonProtocol

import scala.collection.immutable.HashMap
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import cats.implicits._
import org.slf4j.{Logger, LoggerFactory}
import spray.json._
import httpserver.conversion.messages.{CurrencyRateMessage, CurrencyRateMessageV1, CurrencyRateMessageV2}

import java.time.format.DateTimeFormatter
import java.time.LocalDate
import scala.math.BigDecimal.RoundingMode

object CurrencyConverterService extends DefaultJsonProtocol {

  type CashedCurrencyConversionRate = Map[String, BigDecimal]

  val log: Logger = LoggerFactory.getLogger("CurrencyConverterService")

  sealed trait Command

  final case class ConvertCurrencyToEUR(currencyRateMassage: CurrencyRateMessage, replyTo: ActorRef[CurrencyRateMessageResponse]) extends Command

  final case class CashedCurrency(cashKey: String) extends Command

  sealed trait Response

  final case class CurrencyRateMessageResponse(response: Either[ErrorMessage, CurrencyRateMessage]) extends Response

  final case class ErrorMessage(value: String)

  def apply(responseAwaitingTimeFromSource: FiniteDuration, appKey: String, cashAwaitingTime: FiniteDuration): Behavior[Command] = {

    def registry(cashedCurrencyConversionRate: CashedCurrencyConversionRate): Behavior[Command] = {
      log.info(s"Current cash: $cashedCurrencyConversionRate")
      Behaviors.withTimers { timer =>
        Behaviors.receive { (context, message) =>
          implicit val system: ActorSystem[Nothing] = context.system
          implicit val executionContext: ExecutionContextExecutor = system.executionContext

          def getCurrencyRateFromHttpRequest(currencyName: String, request: HttpRequest): BigDecimal = {
            val responseFuture: Future[HttpResponse] = Http().singleRequest(request)
            val response = Await.result(responseFuture.flatMap(resp => Unmarshal(resp.entity).to[String]), responseAwaitingTimeFromSource)
            val currencyRate = response
              .parseJson
              .asJsObject
              .getFields("rates")
              .head
              .asJsObject.getFields(s"$currencyName")
              .head
              .toString
            BigDecimal(currencyRate).setScale(5, RoundingMode.HALF_DOWN)
          }

          def getCurrencyRateFromExchangerateHost(currencyName: String, date: LocalDate): Either[ErrorMessage, BigDecimal] =
            Try {
              val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
              val formattedDay = date.format(dateFormat)
              val request = Get(s"https://api.exchangerate.host/$formattedDay?base=EUR&symbols=$currencyName&places=5")
              getCurrencyRateFromHttpRequest(currencyName, request)
            }.toEither.leftMap(e => ErrorMessage(e.getMessage))

          def getCurrencyRateFromFreecurrencyapi(currencyName: String, date: LocalDate): Either[ErrorMessage, BigDecimal] =
            Try {
              val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
              val formattedDay = date.format(dateFormat)
              val request = Get(s"https://api.freecurrencyapi.com/v1/historical?apikey=$appKey&date_from=$formattedDay&date_to=$formattedDay&base_currency=EUR&currencies=$currencyName")
              getCurrencyRateFromHttpRequest(currencyName, request)
            }.toEither.leftMap(e => ErrorMessage(e.getMessage))

          def getCurrencyRate(currencyName: String, date: LocalDate): (Either[ErrorMessage, BigDecimal], CashedCurrencyConversionRate) = {

            def startCashTimerAndReturnNewCurrencyAndNewCash(
                                                              cashKey: String,
                                                              newMayBeCurrencyRate: Either[ErrorMessage, BigDecimal],
                                                              newCurrencyRate: BigDecimal
                                                            ): (Either[ErrorMessage, BigDecimal], Map[String, BigDecimal]) = {
              timer.startSingleTimer(CashedCurrency(cashKey), cashAwaitingTime)
              (newMayBeCurrencyRate, cashedCurrencyConversionRate + (cashKey -> newCurrencyRate))
            }

            val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val formattedDay = date.format(dateFormat)
            val cashKey = s"$currencyName$formattedDay"
            if (cashedCurrencyConversionRate.contains(cashKey)) {
              log.info(s"Getting currency value from cash")
              (Right(cashedCurrencyConversionRate(cashKey)), cashedCurrencyConversionRate)
            }
            else {
              val newMayBeCurrencyRateFrom1Source = getCurrencyRateFromExchangerateHost(currencyName, date)
              newMayBeCurrencyRateFrom1Source match {
                case Left(e1) =>
                  log.info(s"First currency rate source error: $e1")
                  val newMayBeCurrencyRateFrom2Source = getCurrencyRateFromFreecurrencyapi(currencyName, date)
                  newMayBeCurrencyRateFrom2Source match {
                    case Left(e2) =>
                      (Left(ErrorMessage(s"Error from first source: $e1, Error from second source: $e2")), cashedCurrencyConversionRate)
                    case Right(newCurrencyRate2) =>
                      startCashTimerAndReturnNewCurrencyAndNewCash(cashKey, newMayBeCurrencyRateFrom2Source, newCurrencyRate2)
                  }
                case Right(newCurrencyRate1) =>
                  startCashTimerAndReturnNewCurrencyAndNewCash(cashKey, newMayBeCurrencyRateFrom1Source, newCurrencyRate1)
              }
            }
          }

          message match {
            case ConvertCurrencyToEUR(currencyRateMassage, replyTo) =>
              log.info(s"I have got message for conversion: $currencyRateMassage")
              val (response, newCashedCurrencyConversionRate) = currencyRateMassage.getResponseAndCash(getCurrencyRate)
              log.info(s"Conversion response: $response, cash: $newCashedCurrencyConversionRate")
              replyTo ! response
              registry(newCashedCurrencyConversionRate)

            case CashedCurrency(cashKey) =>
              log.info(s"Cash timer stop")
              registry(cashedCurrencyConversionRate - cashKey)
          }
        }
      }
    }

    registry(HashMap())
  }
}
