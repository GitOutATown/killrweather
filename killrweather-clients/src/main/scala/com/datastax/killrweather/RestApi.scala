package com.datastax.killrweather

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.routing._

//import spray.http._
//import HttpMethods._

class HttpDataFeedActor(timeout: Timeout) extends HttpServiceActor
    with RestRoutes {
                  
    println("++++>>> HttpDataFeedActor, constructor")  
    
    def receive = runRoute {
        path("ping") {
            get {
                complete("PONG")
            }
        }
    }
        
    //def receive = runRoute(routes)
}

trait RestRoutes extends HttpService {
      //with BoxOfficeApi
      //with EventMarshalling {
    import StatusCodes._

    //def routes: Route = feedRoute // eventsRoute ~ eventRoute ~ ticketsRoute
    
    //def feedRoute = 

}




