/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.mongodb

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.mongodb.scaladsl.MongoSource
import akka.stream.scaladsl.{Sink, Source}
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.bson.collection.immutable.Document
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._

class MongoSourceSpec
    extends WordSpec
    with ScalaFutures
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with MustMatchers {

  //#init-mat
  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  //#init-mat

  override protected def beforeAll(): Unit =
    Await.result(db.drop().toFuture(), 5.seconds)

  java.util.logging.Logger.getLogger("org.mongodb.driver").setLevel(java.util.logging.Level.SEVERE)

  // #macros-codecs
  case class Number(_id: Int)
  val codecRegistry = fromRegistries(fromProviders(classOf[Number]), DEFAULT_CODEC_REGISTRY)
  // #macros-codecs

  // #init-connection
  private val client = MongoClient(s"mongodb://localhost:27017")
  private val db = client.getDatabase("alpakka-mongo")
  private val numbersColl = db.getCollection("numbers")
  // #init-connection

  // #init-connection-codec
  private val numbersObjectColl = db.getCollection("numbers").withCodecRegistry(codecRegistry)
  // #init-connection-codec

  implicit val defaultPatience =
    PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  override def afterEach(): Unit =
    Await.result(numbersColl.deleteMany(Document()).toFuture(), 5.seconds)

  override def afterAll(): Unit =
    Await.result(system.terminate(), 5.seconds)

  private def seed() = {
    val numbers = 1 until 10
    Await.result(numbersColl.insertMany {
      numbers.map { number =>
        Document(s"{_id:$number}")
      }
    }.toFuture, 5.seconds)
    numbers
  }

  "MongoSourceSpec" must {

    "stream the result of a simple Mongo query" in {
      val data: Seq[Int] = seed()

      //#create-source
      val source: Source[Document, NotUsed] =
        MongoSource(numbersColl.find())
      //#create-source

      //#run-source
      val rows: Future[Seq[Document]] = source.runWith(Sink.seq)
      //#run-source

      rows.futureValue.map(_.getInteger("_id")) must contain theSameElementsAs data
    }

    "support codec registry to read case class objects" in {
      val data: Seq[Number] = seed().map(Number)

      //#create-source-codec
      val source: Source[Number, NotUsed] =
        MongoSource[Number](numbersObjectColl.find())
      //#create-source-codec

      //#run-source-codec
      val rows: Future[Seq[Number]] = source.runWith(Sink.seq)
      //#run-source-codec

      rows.futureValue must contain theSameElementsAs data
    }

    "support multiple materializations" in {
      val data: Seq[Int] = seed()
      val numbersObservable = numbersColl.find()

      val source = MongoSource(numbersObservable)

      source.runWith(Sink.seq).futureValue.map(_.getInteger("_id")) must contain theSameElementsAs data
      source.runWith(Sink.seq).futureValue.map(_.getInteger("_id")) must contain theSameElementsAs data
    }

    "stream the result of Mongo query that results in no data" in {
      val numbersObservable = numbersColl.find()

      val rows = MongoSource(numbersObservable).runWith(Sink.seq).futureValue

      rows mustBe empty
    }
  }
}
