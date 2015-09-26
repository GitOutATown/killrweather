/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.killrweather

import java.net.InetSocketAddress

import scala.concurrent.Future
import scala.concurrent.duration._
import org.reactivestreams.Publisher
import akka.stream.{ActorFlowMaterializerSettings, ActorFlowMaterializer}
import akka.actor._
import akka.cluster.Cluster
import akka.util.Timeout
//import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.routing.BalancingPool
import kafka.producer.ProducerConfig
import kafka.serializer.StringEncoder
//import com.typesafe.config.ConfigFactory
import com.datastax.spark.connector.embedded._
import com.datastax.killrweather.cluster.ClusterAwareNodeGuardian
import com.datastax.spark.connector.embedded.KafkaEvent.KafkaMessageEnvelope

import akka.io.IO
import spray.can.Http
import akka.pattern.ask
import com.typesafe.config.{ Config, ConfigFactory }

/** Run with: sbt clients/run for automatic data file import to Kafka.
  *
  * To do manual curl import:
  * {{{
  *   curl -v -X POST --header "X-DATA-FEED: ./data/load/sf-2008.csv.gz" http://127.0.0.1:8080/weather/data
  * }}}
  *
  * NOTE does not support running on Windows
  */
object KafkaDataIngestionApp extends App {
    
  println("====>>> STARTING KafkaDataIngestionApp")
    
  //val config = ConfigFactory.load
  //val host = config.getString("killrweather.http.host")
  //val port = config.getInt("killrweather.http.port")

  /** Creates the ActorSystem. */
  implicit val system = ActorSystem("KillrWeather", ConfigFactory.parseString("akka.remote.netty.tcp.port = 2551"))

  //implicit val executionContext = system.dispatcher
  
 // implicit val timeout = requestTimeout(config)
  
  /* The root supervisor and fault tolerance handler of the data ingestion nodes. */
  val guardian = system.actorOf(Props[HttpNodeGuardian], "node-guardian")
  
  //val api = system.actorOf(Props(new RestApi(timeout)), "httpInterface")
  //val response = IO(Http).ask(Http.Bind(listener = api, interface = host, port = port)) //<co id="ch02_startServer"/>
  
  //val response = IO(Http).ask(Http.Bind(listener = guardian, interface = host, port = port)) //<co id="ch02_startServer"/>

  //shutdownIfNotBound(response)

  system.registerOnTermination {
    guardian ! PoisonPill
  }
}

/**
 * Transforms raw weather data .gz files to line data and publishes to the Kafka topic.
 *
 * Simulates real time weather events individually sent to the KillrWeather App for demos.
 * @see [[HttpDataFeedActor]] for sending data files via curl instead.
 *
 * Because we run locally vs against a cluster as a demo app, we keep that file size data small.
 * Using rdd.toLocalIterator will consume as much memory as the largest partition in this RDD,
 * which in this use case is 360 or fewer (if current year before December 31) small Strings.
 *
 * The ingested data is sent to the kafka actor for processing in the stream.
 */
final class HttpNodeGuardian extends ClusterAwareNodeGuardian 
    with ClientHelper
    with RequestTimeout 
    with ShutdownIfNotBound {

  cluster.joinSeedNodes(Vector(cluster.selfAddress))

  val router = context.actorOf(BalancingPool(5).props(
    Props(new KafkaPublisherActor(KafkaHosts, KafkaBatchSendSize))), "kafka-ingestion-router")

  cluster registerOnMemberUp {

    /* As http data is received, publishes to Kafka. */
    /*context.actorOf(BalancingPool(10).props(
      Props(new HttpDataFeedActor(router))), "dynamic-data-feed")*/
    
    val config = ConfigFactory.load
    val host = config.getString("killrweather.http.host")
    val port = config.getInt("killrweather.http.port")
    
    implicit val system = context.system  
    implicit val executionContext = system.dispatcher
    implicit val timeout = requestTimeout(config)
    
    /* As http data is received, publishes to Kafka. */
    val feedApi = system.actorOf(BalancingPool(10)
        .props(Props(new HttpDataFeedActor(timeout))), "dynamic-data-feed")
        
    val response = IO(Http).ask(Http.Bind(listener = feedApi, interface = host, port = port))
    shutdownIfNotBound(response)

    log.info("Starting data ingestion on {}.", cluster.selfAddress)

    /* Handles initial data ingestion in Kafka for running as a demo. */
    for (fs <- initialData; data <- fs.data) {
      log.info("Sending {} to Kafka", data)
      router ! KafkaMessageEnvelope[String, String](KafkaTopic, KafkaKey, data)
    }
    
    log.info("File ingestion completed {}.", cluster.selfAddress)
  }

  def initialized: Actor.Receive = {
    case WeatherEvent.TaskCompleted => // ignore for now
  }
}

/** The KafkaPublisherActor receives initial data on startup (because this
  * is for a runnable demo) and also receives data in runtime.
  *
  * Publishes [[com.datastax.spark.connector.embedded.KafkaEvent.KafkaMessageEnvelope]]
  * to Kafka on a sender's behalf. Multiple instances are load-balanced in the [[HttpNodeGuardian]].
  */
class KafkaPublisherActor(val producerConfig: ProducerConfig) extends KafkaProducerActor[String,String] {

  def this(hosts: Set[String], batchSize: Int) = this(KafkaProducer.createConfig(
    hosts, batchSize, "async", classOf[StringEncoder].getName))

}

/** An Http server receiving requests containing header or entity based data which it sends to Kafka.
  * by delegating to the [[KafkaPublisherActor]]. */
/*class HttpDataFeedActor(kafka: ActorRef) extends Actor with ActorLogging with ClientHelper {
    
  println("++++>>> In HttpDataFeedActor TOP")

  import Sources._
  import context.dispatcher
  import akka.stream.scaladsl._
  import akka.stream.scaladsl.Flow

  implicit val system = context.system

  implicit val askTimeout: Timeout = 500.millis

  implicit val materializer = ActorFlowMaterializer(
    ActorFlowMaterializerSettings(system)
  )

  /*
  val requestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.POST, Uri.Path("/weather/data"), headers, entity, _) =>
      HttpSource.unapply(headers,entity).collect { 
          case hs: HeaderSource =>
            hs.extract.foreach({ fs: FileSource =>
              log.info(s"Ingesting {} and publishing {} data points to Kafka topic {}.", fs.name, fs.data.size, KafkaTopic)
              kafka ! KafkaMessageEnvelope[String, String](KafkaTopic, KafkaKey, fs.data:_*)
            })
            HttpResponse(200, entity = HttpEntity(MediaTypes.`text/html`, s"POST [${hs.sources.mkString}] successful."))
      }.getOrElse(HttpResponse(404, entity = "Unsupported request") )
    case _: HttpRequest =>
      HttpResponse(400, entity = "Unsupported request")
  }

  val http = Http(system)
    .bind(interface = HttpHost, port = HttpPort)
    .map { 
      case connection  =>
          log.info("Accepted new connection from " + connection.remoteAddress)
          connection.handleWithSyncHandler(requestHandler)
    }
  */
  //println("++++>>> In HttpDataFeedActor, HttpHost: " + HttpHost + " HttpPort: " + HttpPort)
  //println("++++>>> In HttpDataFeedActor Http(system): " + http)

  /*
  def receive : Actor.Receive = {
    case e =>
  }*/
}*/

// RW added ---------------------- //

trait RequestTimeout {
  import scala.concurrent.duration._
  def requestTimeout(config: Config): Timeout = { //<co id="ch02_timeout_spray_can"/>
    val t = config.getString("spray.can.server.request-timeout")
    val d = Duration(t)
    FiniteDuration(d.length, d.unit)
  }
}

trait ShutdownIfNotBound {
  import scala.concurrent.ExecutionContext
  import scala.concurrent.Future

  def shutdownIfNotBound(f: Future[Any]) //<co id="ch02_shutdownIfNotBound"/>
    (implicit system: ActorSystem, ec: ExecutionContext) = {
    println("---->>> shutdownIfNotBound TOP")
    f.mapTo[Http.Event].map {
      case Http.Bound(address) =>
        println(s"----> REST interface bound to $address")
      case Http.CommandFailed(cmd) => //<co id="http_command_failed"/>
        println(s"----> REST interface could not bind: ${cmd.failureMessage}, shutting down.")
        system.shutdown()
    }.recover {
      case e: Throwable =>
        println(s"----> Unexpected error binding to HTTP: ${e.getMessage}, shutting down.")
        system.shutdown()
    }
  }
}



