package io.neilord

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import akka.stream.scaladsl._
import scala.util.Random
import akka.http.scaladsl.model._

object BootWithFlow extends App with BSONUtils {

  implicit val system = ActorSystem("Streams")
  implicit val materializer = ActorFlowMaterializer()

  //Start the server
  val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
    Http().bind(interface = "localhost", port = 8091)

  val bindingFuture: Future[Http.ServerBinding] = serverSource.to(Sink.foreach {
    connection =>
      println(s"Connection accepted from ${connection.remoteAddress}")
      //connection handleWith { broadcastZipFlow }
      connection handleWith { broadcastMergeFlow }
  }).run()

  //Some dummy steps that totally disregard the request, and return an entry of their choosing.
  val step1 = Flow[HttpRequest].mapAsync[String](1)(getTickerHandler("GOOG"))
  val step2 = Flow[HttpRequest].mapAsync[String](1)(getTickerHandler("AAPL"))
  val step3 = Flow[HttpRequest].mapAsync[String](1)(getTickerHandler("MSFT"))

  val mapStringToResponse: Flow[String, HttpResponse, Unit] = Flow[String].map[HttpResponse](
    (in: String) => HttpResponse(status = StatusCodes.OK, entity = in)
  )

  //"To Many"
  val bCast3 = Broadcast[HttpRequest](3)

  //"From Many" - Merge takes the first available response
  val merge3 = Merge[String](3)

  //"From Many" - ZipWith combines the inputs (3 here) into some result (HttpResponse). Set them using .inputX.
  val zip3 = ZipWith[String, String, String, HttpResponse] {
    (inp1, inp2, inp3) => new HttpResponse(status = StatusCodes.OK, entity = inp1 + inp2 + inp3)
  }

  val broadcastMergeFlow = Flow() {
    implicit builder =>
      import FlowGraph.Implicits._

      val bcast = builder.add(bCast3)
      val merge = builder.add(merge3)
      val merge1 = builder.add(Merge[HttpResponse](1))

      //TODO: How do we structure this such that the extra merge1 is not needed?
      bcast ~> step1 ~> merge
      bcast ~> step2 ~> merge ~> mapStringToResponse ~> merge1
      bcast ~> step3 ~> merge

      (bcast.in, merge1.out)
  }

  val broadcastZipFlow = Flow() {
    implicit builder =>
      import FlowGraph.Implicits._

      val bcast = builder.add(bCast3)
      val zip = builder.add(zip3)

      bcast ~> step1 ~> zip.in0
      bcast ~> step2 ~> zip.in1
      bcast ~> step3 ~> zip.in2

      (bcast.in, zip.out)
  }

// OLD format:
//
// //There's a faked delay in step1-3, so you don't know which one you will get, it's realistic.
//  val broadCastMergeFlow = Flow[HttpRequest, HttpResponse]() {
//    implicit builder =>
//      //Input -> Splitter -> [ Actions ] -> Merge (take first policy) -> Create an HttpResponse -> Output
//            bCast ~> step1 ~> merge
//      in ~> bCast ~> step2 ~> merge ~> mapStringToResponse ~> out
//            bCast ~> step3 ~> merge
//      (in, out)
//  }
//
//  val broadCastZipFlow = Flow[HttpRequest, HttpResponse]() {
//    implicit builder =>
//      //Input -> Splitter -> [ Actions ] -> Zip (combine all policy, ours makes an HttpResponse) -> Output
//            bCast ~> step1 ~> zip.input1
//      in ~> bCast ~> step2 ~> zip.input2 ~> out
//            bCast ~> step3 ~> zip.input3
//      (in, out)
//  }

  def getTickerHandler(tickName: String)(request: HttpRequest): Future[String] = {
    // query the database
    val tickerFO = Database.findTicker(tickName)
    Thread.sleep(Random.nextInt(10) * 1000)

    tickerFO map {
      _ map convertToString getOrElse ""
    }
  }

}
