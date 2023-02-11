package httpserver

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
object CurrencyConverterStarter {
  private def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit = {
    import system.executionContext

    val futureBinding = Http().newServerAt("localhost", 8080).bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    val rootBehavior = Behaviors.setup[Nothing] { context =>

      implicit val system: ActorSystem[Nothing] = context.system

      val responseAwaitingTimeFromSourceAmount =
        system.settings.config.getLong("CurrencyConverterServiceConfig.responseAwaitingTimeFromSourceAmount")
      val responseAwaitingTimeFromSourceUnit =
        system.settings.config.getString("CurrencyConverterServiceConfig.responseAwaitingTimeFromSourceUnit")
      val appKey = system.settings.config.getString("CurrencyConverterServiceConfig.appKey")
      val cashAwaitingTimeAmount =
        system.settings.config.getLong("CurrencyConverterServiceConfig.cashAwaitingTimeAmount")
      val cashAwaitingTimeUnit =
        system.settings.config.getString("CurrencyConverterServiceConfig.cashAwaitingTimeUnit")

      val responseAwaitingTimeFromSource = FiniteDuration(responseAwaitingTimeFromSourceAmount, responseAwaitingTimeFromSourceUnit)
      val cashAwaitingTime = FiniteDuration(cashAwaitingTimeAmount, cashAwaitingTimeUnit)

      val currencyConverterService = context.spawn(
        CurrencyConverterService(responseAwaitingTimeFromSource, appKey, cashAwaitingTime), "CurrencyConverterService")
      context.watch(currencyConverterService)

      val routes = new Routes(currencyConverterService)
      startHttpServer(routes.baseConversionRote)

      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "CurrencyConvertingHttpServer")

  }
}
