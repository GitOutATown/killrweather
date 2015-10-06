/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.    See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.    You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
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
//import akka.http.scaladsl.Http // Using Spray instead
import akka.http.scaladsl.model._
import akka.routing.BalancingPool
import kafka.producer.ProducerConfig
import kafka.serializer.StringEncoder
import com.datastax.spark.connector.embedded._
import com.datastax.killrweather.cluster.ClusterAwareNodeGuardian
import com.datastax.spark.connector.embedded.KafkaEvent.KafkaMessageEnvelope

import akka.io.IO
import spray.can.Http
import akka.pattern.ask
import com.typesafe.config.{ Config, ConfigFactory }

/** Run with: sbt clients/run for automatic data file import to Kafka.
    *
    * To do manual httpie import:
    * This example uses httpie
    * https://github.com/jkbrzt/httpie
    * {{{
            http POST http://127.0.0.1:5000/weather/data X-DATA-FEED:./data/test_load/sf-2008.csv
    * }}}
    *
    */
object KafkaDataIngestionApp extends App {
        
    /** Creates the ActorSystem. */
    val system = ActorSystem("KillrWeather", ConfigFactory.parseString("akka.remote.netty.tcp.port = 2551"))
    
    /* The root supervisor and fault tolerance handler of the data ingestion nodes. */
    val guardian = system.actorOf(Props[HttpNodeGuardian], "node-guardian")

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

    val kafkaRouter = context.actorOf(BalancingPool(5).props(
        Props(new KafkaPublisherActor(KafkaHosts, KafkaBatchSendSize))), "kafka-ingestion-router")

    cluster registerOnMemberUp {
        
        val config = ConfigFactory.load
        val host = config.getString("killrweather.http.host")
        val port = config.getInt("killrweather.http.port")
        
        log.info("HttpNodeGuardian, host: " + host)
        log.info("HttpNodeGuardian, port: " + port)
        
        implicit val system = context.system    
        implicit val executionContext = system.dispatcher
        implicit val timeout = requestTimeout(config)

        log.info("Starting data ingestion on {}.", cluster.selfAddress)
        log.info("DefaultPath: " + DefaultPath)

        /* Handles initial data ingestion in Kafka for running as a demo. */
        for(
            fs <- initialData; 
            data <- fs.data // data is a string
        ){
            log.info("Sending {} to Kafka", data)
            kafkaRouter ! KafkaMessageEnvelope[String, String](KafkaTopic, KafkaKey, data)
        }
        
        log.info("On startup file ingestion completed {}.", cluster.selfAddress)
        
        log.info("Creating feed ingestion API")
        
        /* As http data is received, publishes to Kafka. */
        val feedApi = system.actorOf(BalancingPool(10)
            .props(Props(new HttpDataFeedActor(kafkaRouter))), "dynamic-data-feed")
    
        val response = IO(Http).ask(Http.Bind(listener = feedApi, interface = host, port = port))
        shutdownIfNotBound(response)
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
        f.mapTo[Http.Event].map {
            case Http.Bound(address) =>
                println(s"REST interface bound to $address")
            case Http.CommandFailed(cmd) => //<co id="http_command_failed"/>
                println(s"REST interface could not bind: ${cmd.failureMessage}, shutting down.")
                system.shutdown()
        }.recover {
            case e: Throwable =>
                println(s"Unexpected error binding to HTTP: ${e.getMessage}, shutting down.")
                system.shutdown()
        }
    }
}



