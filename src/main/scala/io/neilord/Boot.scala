package io.neilord

import akka.actor.ActorSystem
import akka.stream.FlowMaterializer
import scala.concurrent.Future
import akka.http.model.HttpMethods._
import akka.http.model._
import akka.http._
import reactivemongo.bson.BSONDocument
import play.modules.reactivemongo.json.BSONFormats
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext.Implicits.global
import akka.stream.scaladsl.Flow

object Boot extends App {

  //Which actor system to use
  implicit val system = ActorSystem("Streams")
  implicit val materializer = FlowMaterializer()

  //Start the server
  val serverBinding = Http().bind(interface = "localhost", port = 8091)
  serverBinding.connections.foreach { connection =>
    println(s"Connection accepted from ${connection.remoteAddress}")
    connection handleWith { Flow[HttpRequest] mapAsync asyncHandler }
    //connection.handleWithAsyncHandler(asyncHandler) is equivalent here
  }

  def asyncHandler: HttpRequest => Future[HttpResponse] = {
    //Match specific path. Returns all known tickers
    case HttpRequest(GET, Uri.Path("/getAllTickers"), _, _, _) =>
      Database.findAllTickers().map {
        input => HttpResponse(entity = convertToString(input))
      }

    //TODO what is a nice way to pull ID into path not query param
    //Match specific path. Returns ticker based on query param
    case request@HttpRequest(GET, Uri.Path("/get"), _, _, _) =>
      request.uri.query.get("ticker") map {
        queryParam =>
          //Query the DB
          val tickerBsonFuture = Database.findTicker(queryParam)

          tickerBsonFuture map {
            tickerBsonOption => tickerBsonOption map {
              tickerBson => HttpResponse(entity = convertToString(tickerBson))
            } getOrElse HttpResponse(status = StatusCodes.OK)
          }

      } getOrElse Future.successful(HttpResponse(status = StatusCodes.OK))

    case HttpRequest(_, _, _, _, _) => Future.successful(HttpResponse(status = StatusCodes.NotFound))
  }

  //use the play json libraries to convert BSON to json
  def convertToString(input: List[BSONDocument]) : String = {
    input
      .map(f => convertToString(f))
      .mkString("[", ",", "]")
  }

  def convertToString(input: BSONDocument) : String = {
    Json.stringify(BSONFormats.toJSON(input))
  }
}
