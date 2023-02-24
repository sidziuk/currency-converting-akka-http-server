package httpserver

import akka.actor.typed.{ActorRef, ActorSystem, DispatcherSelector}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}
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

      val cashAwaitingTimeAmount =
        system.settings.config.getLong("CurrencyConverterServiceConfig.cashAwaitingTimeAmount")
      val cashAwaitingTimeUnit =
        system.settings.config.getString("CurrencyConverterServiceConfig.cashAwaitingTimeUnit")
      val cashAwaitingTime = FiniteDuration(cashAwaitingTimeAmount, cashAwaitingTimeUnit)

//      implicit val executionContext: ExecutionContext =
//        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("workin-dispatcher"))

      val cashService: ActorRef[CashService.Command] = context.spawn(CashService(cashAwaitingTime), "CashService", DispatcherSelector.fromConfig("working-dispatcher"))
      context.watch(cashService)

      val routes = new Routes(cashService)
      startHttpServer(routes.baseConversionRote)

      Behaviors.empty
    }
    ActorSystem[Nothing](rootBehavior, "CurrencyConvertingHttpServer")
  }
}
