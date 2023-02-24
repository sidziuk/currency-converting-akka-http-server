package httpserver

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.immutable.HashMap
import scala.concurrent.duration.FiniteDuration

object CashService  {

  type CashedCurrencyConversionRate = Map[String, BigDecimal]

  val log: Logger = LoggerFactory.getLogger("CashConverterService")

  sealed trait Command
  final case class SetNewValue(cashKey: String, newCurrencyRate: BigDecimal) extends Command
  final case class GetCashedCurrencyRate(cashKey: String, replyTo: ActorRef[CashedValue]) extends Command
  final case class CashedCurrency(cashKey: String) extends Command

  sealed trait Response
  final case class CashedValue(cashedCurrencyRate: Option[BigDecimal]) extends Response

  def apply(cashAwaitingTime: FiniteDuration): Behavior[Command] = {

    def cashActor(cashedCurrencyConversionRate: CashedCurrencyConversionRate): Behavior[Command] = {
      log.info(s"Current cash: $cashedCurrencyConversionRate")
      Behaviors.withTimers { timer =>
        Behaviors.receiveMessage {
          case SetNewValue(cashKey, newCurrencyRate) =>
            log.info(s"I have got new value for cash: $cashKey, $newCurrencyRate")
            timer.startSingleTimer(CashedCurrency(cashKey), cashAwaitingTime)
            cashActor(cashedCurrencyConversionRate + (cashKey -> newCurrencyRate))
          case GetCashedCurrencyRate(cashKey, replyTo) =>
            log.info(s"Send cashed value")
            replyTo ! CashedValue(cashedCurrencyConversionRate.get(cashKey))
            Behaviors.same

          case CashedCurrency(cashKey) =>
            log.info(s"Cash timer stop for cash key: $cashKey")
            cashActor(cashedCurrencyConversionRate - cashKey)
        }
      }
    }
    cashActor(HashMap())
  }
}

