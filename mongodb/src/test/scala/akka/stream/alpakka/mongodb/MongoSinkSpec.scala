/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.mongodb

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.mongodb.scaladsl.MongoSink
import akka.stream.alpakka.mongodb.scaladsl.DocumentUpdate
import akka.stream.scaladsl.Source
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.{MongoClient, MongoCollection}
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.Updates._
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent._
import scala.concurrent.duration._

class MongoSinkSpec
    extends WordSpec
    with ScalaFutures
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with MustMatchers {

  // case class and codec for mongodb macros
  case class Number(_id: Int)

  val codecRegistry = fromRegistries(fromProviders(classOf[Number]), DEFAULT_CODEC_REGISTRY)

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  override protected def beforeAll(): Unit =
    Await.result(db.drop().toFuture(), 5.seconds)

  private val client = MongoClient(s"mongodb://localhost:27017")
  private val db = client.getDatabase("alpakka-mongo").withCodecRegistry(codecRegistry)
  private val numbersColl = db.getCollection("numbersSink")
  //#init-connection-codec
  private val numbersObjectColl: MongoCollection[Number] = db.getCollection("numbersSink")
  //#init-connection-codec

  implicit val defaultPatience =
    PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  override def afterEach(): Unit =
    Await.result(numbersColl.deleteMany(Document()).toFuture(), 5.seconds)

  override def afterAll(): Unit =
    Await.result(system.terminate(), 5.seconds)

  val testRange = 0 until 10

  def insertTestRange(): Unit =
    numbersColl.insertMany(testRange.map(i => Document(s"""{"value":$i}"""))).toFuture().futureValue

  "MongoSinkSpec" must {

    "save with insertOne" in {
      val source = Source(testRange).map(i => Document(s"""{"value":$i}"""))

      source.runWith(MongoSink.insertOne(2, numbersColl)).futureValue

      val found = numbersColl.find().toFuture().futureValue

      found.map(_.getInteger("value")) must contain theSameElementsAs testRange
    }

    "save with insertOne and codec support" in {
      val testRangeObjects = testRange.map(Number(_))
      val source = Source(testRangeObjects)

      source.runWith(MongoSink.insertOne[Number](2, numbersObjectColl)).futureValue

      val found = numbersObjectColl.find().toFuture().futureValue

      found must contain theSameElementsAs testRangeObjects
    }

    "save with insertMany" in {
      val source = Source(testRange).map(i => Document(s"""{"value":$i}"""))

      source.grouped(2).runWith(MongoSink.insertMany(2, numbersColl)).futureValue

      val found = numbersColl.find().toFuture().futureValue

      found.map(_.getInteger("value")) must contain theSameElementsAs testRange
    }

    "save with insertMany and codec support" in {
      val testRangeObjects = testRange.map(Number(_))
      val source = Source(testRangeObjects)

      source.grouped(2).runWith(MongoSink.insertMany[Number](2, numbersObjectColl)).futureValue

      val found = numbersObjectColl.find().toFuture().futureValue

      found must contain theSameElementsAs testRangeObjects
    }

    "update with updateOne" in {
      insertTestRange()

      val source = Source(testRange).map(
        i => DocumentUpdate(filter = Filters.equal("value", i), update = set("updateValue", i * -1))
      )

      source.runWith(MongoSink.updateOne(2, numbersColl)).futureValue

      val found = numbersColl.find().toFuture().futureValue

      found.map(doc => doc.getInteger("value") -> doc.getInteger("updateValue")) must contain theSameElementsAs testRange
        .map(i => i -> i * -1)
    }

    "update with updateMany" in {
      insertTestRange()

      val source = Source
        .single(0)
        .map(
          _ => DocumentUpdate(filter = Filters.gte("value", 0), update = set("updateValue", 0))
        )

      source.runWith(MongoSink.updateMany(2, numbersColl)).futureValue

      val found = numbersColl.find().toFuture().futureValue

      found.map(doc => doc.getInteger("value") -> doc.getInteger("updateValue")) must contain theSameElementsAs testRange
        .map(i => i -> 0)
    }

    "delete with deleteOne" in {
      insertTestRange()

      val source = Source(testRange).map(i => Filters.equal("value", i))

      source.runWith(MongoSink.deleteOne(2, numbersColl)).futureValue

      val found = numbersColl.find().toFuture().futureValue

      found mustBe empty
    }

    "delete with deleteMany" in {
      insertTestRange()

      val source = Source.single(0).map(_ => Filters.gte("value", 0))

      source.runWith(MongoSink.deleteMany(2, numbersColl)).futureValue

      val found = numbersColl.find().toFuture().futureValue

      found mustBe empty
    }
  }

  private class ParadoxSnippet1() {
    //#insertOne
    val source: Source[Document, NotUsed] = ???
    source.runWith(MongoSink.insertOne(parallelism = 2, collection = numbersColl))
    //#insertOne
  }

  private class ParadoxSnippetCodec1() {
    //#insertOneCodec
    val source: Source[Number, NotUsed] = ???
    source.runWith(MongoSink.insertOne[Number](parallelism = 2, collection = numbersObjectColl))
    //#insertOneCodec
  }

  private class ParadoxSnippet2() {
    //#insertMany
    val source: Source[Seq[Document], NotUsed] = ???
    source.runWith(MongoSink.insertMany(parallelism = 2, collection = numbersColl))
    //#insertMany
  }

  private class ParadoxSnippetCodec2() {
    //#insertManyCodec
    val source: Source[Seq[Number], NotUsed] = ???
    source.runWith(MongoSink.insertMany[Number](parallelism = 2, collection = numbersObjectColl))
    //#insertManyCodec
  }

  private class ParadoxSnippet3() {
    //#updateOne
    import org.mongodb.scala.model.{Filters, Updates}

    val source: Source[DocumentUpdate, NotUsed] = Source
      .single(DocumentUpdate(filter = Filters.eq("id", 1), update = Updates.set("updateValue", 0)))

    source.runWith(MongoSink.updateOne(2, numbersColl))
    //#updateOne
  }

  private class ParadoxSnippet4() {
    //#deleteOne
    val source: Source[Bson, NotUsed] = Source.single(Filters.eq("id", 1))
    source.runWith(MongoSink.deleteOne(2, numbersColl))
    //#deleteOne
  }
}
