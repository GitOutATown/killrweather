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
import spray.json.DefaultJsonProtocol

class HttpDataFeedActor(kafka: ActorRef) extends HttpServiceActor
    with RestRoutes {
                          
    def receive = {
        println("--->HttpDataFeedActor runRoute...")
        runRoute(routes)
    }
    
    def obtainKafkaApi = kafka // TODO: HACK!!!
}

trait RestRoutes extends HttpService
    with KafkaEndpointApi {
    import Weather._
        
    def routes: Route = feedRoute
    
    def feedRoute = path("weather"/"data") {
        post {
            println("--->feedRoute post...")
            headerValueByName("X-DATA-FEED") { filePath =>
                complete(kafkaFileIngest(filePath)) // TODO: Should this be a message? No, I think local function is appropriate
            }
        } ~
        put {
            println("--->feedRoute put...")
            // RW: Maybe this transformation doesn't belong here and the stringified JSON should be sent to Spark as is, and then Spark could convert it to the case class. I need to examine what Spark is currently doing.
            handleWith { rawRecord: RawWeatherData => 
                print("--->rawRecord: " + rawRecord)
                rawRecord 
            }
        }
    }
}

trait KafkaEndpointApi {
    import Sources._
    import java.io.{BufferedInputStream, FileInputStream, File => JFile}
    import com.typesafe.config.ConfigFactory
    import com.datastax.spark.connector.embedded.KafkaEvent.KafkaMessageEnvelope
    
    // TODO: HACK!
    def obtainKafkaApi(): ActorRef
    
    val kafkaRouter = obtainKafkaApi
    
    private val config = ConfigFactory.load
    protected val DefaultExtension = config.getString("killrweather.data.file.extension")
    protected val KafkaTopic = config.getString("kafka.topic.raw")
    protected val KafkaKey = config.getString("kafka.group.id")
    
    def kafkaFileIngest(filePath: String) = {
        
        println("kafkaIngest, filePath: " + filePath)
        
        val fs = FileSource(new JFile(filePath))
    
        for(data <- fs.data){
            println("Sending to Kafka: " + data)
            kafkaRouter ! KafkaMessageEnvelope[String, String](KafkaTopic, KafkaKey, data)
        }
        
        // TODO: HACK! Finish learning how to create proper 
        // HTTP response in Spray!
        "---->>> Kafka ingest completed."
    }
}




