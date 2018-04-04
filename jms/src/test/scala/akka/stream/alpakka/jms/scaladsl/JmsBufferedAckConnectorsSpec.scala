/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.jms.scaladsl

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import javax.jms.{JMSException, TextMessage}

import akka.Done
import akka.stream.alpakka.jms._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{KillSwitch, KillSwitches, ThrottleMode}
import org.apache.activemq.{ActiveMQConnectionFactory, ActiveMQSession}
import org.scalatest.Inspectors._

import scala.annotation.tailrec
import scala.collection.{mutable, SortedSet}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class JmsBufferedAckConnectorsSpec extends JmsSpec {

  override implicit val patienceConfig = PatienceConfig(2.minutes)

  "The JMS Ack Connectors" should {
    "publish and consume strings through a queue" in withServer() { ctx =>
      val connectionFactory = new ActiveMQConnectionFactory(ctx.url)

      val jmsSink: Sink[String, Future[Done]] = JmsProducer.textSink(
        JmsProducerSettings(connectionFactory).withQueue("test")
      )

      val in = 0 to 25 map (i => ('a' + i).asInstanceOf[Char].toString)
      Source(in).runWith(jmsSink)

      val jmsSource: Source[AckEnvelope, KillSwitch] = JmsConsumer.ackSource(
        JmsConsumerSettings(connectionFactory).withSessionCount(5).withBufferSize(5).withQueue("test")
      )

      val result = jmsSource
        .take(in.size)
        .map(env => (env, env.message.asInstanceOf[TextMessage].getText))
        .map { case (env, text) => env.acknowledge(); text }
        .runWith(Sink.seq)

      result.futureValue should contain theSameElementsAs in
    }

    "publish and consume JMS text messages with properties through a queue" in withServer() { ctx =>
      val connectionFactory = new ActiveMQConnectionFactory(ctx.url)

      val jmsSink: Sink[JmsTextMessage, Future[Done]] = JmsProducer(
        JmsProducerSettings(connectionFactory).withQueue("numbers")
      )

      val msgsIn = 1 to 100 map { n =>
        JmsTextMessage(n.toString)
          .withProperty("Number", n)
          .withProperty("IsOdd", n % 2 == 1)
          .withProperty("IsEven", n % 2 == 0)
      }

      Source(msgsIn).runWith(jmsSink)

      //#create-jms-source
      val jmsSource: Source[AckEnvelope, KillSwitch] = JmsConsumer.ackSource(
        JmsConsumerSettings(connectionFactory).withSessionCount(5).withBufferSize(5).withQueue("numbers")
      )
      //#create-jms-source

      //#run-jms-source
      val result = jmsSource
        .take(msgsIn.size)
        .map { env =>
          env.acknowledge()
          env.message
        }
        .runWith(Sink.seq)
      //#run-jms-source

      // The sent message and the receiving one should have the same properties
      val sortedResult = result.futureValue.sortBy(msg => msg.getIntProperty("Number"))
      forAll(sortedResult.zip(msgsIn)) {
        case (out, in) =>
          out.getIntProperty("Number") shouldEqual in.properties("Number")
          out.getBooleanProperty("IsOdd") shouldEqual in.properties("IsOdd")
          out.getBooleanProperty("IsEven") shouldEqual in.properties("IsEven")
      }
    }

    "publish JMS text messages with properties through a queue and consume them with a selector" in withServer() {
      ctx =>
        val connectionFactory = new ActiveMQConnectionFactory(ctx.url)

        val jmsSink: Sink[JmsTextMessage, Future[Done]] = JmsProducer(
          JmsProducerSettings(connectionFactory).withQueue("numbers")
        )

        val msgsIn = 1 to 100 map { n =>
          JmsTextMessage(n.toString)
            .withProperty("Number", n)
            .withProperty("IsOdd", n % 2 == 1)
            .withProperty("IsEven", n % 2 == 0)
        }
        Source(msgsIn).runWith(jmsSink)

        val jmsSource = JmsConsumer.ackSource(
          JmsConsumerSettings(connectionFactory)
            .withSessionCount(5)
            .withBufferSize(5)
            .withQueue("numbers")
            .withSelector("IsOdd = TRUE")
        )

        val oddMsgsIn = msgsIn.filter(msg => msg.body.toInt % 2 == 1)
        val result = jmsSource
          .take(oddMsgsIn.size)
          .map { env =>
            env.acknowledge()
            env.message
          }
          .runWith(Sink.seq)
        // We should have only received the odd numbers in the list

        val sortedResult = result.futureValue.sortBy(msg => msg.getIntProperty("Number"))
        forAll(sortedResult.zip(oddMsgsIn)) {
          case (out, in) =>
            out.getIntProperty("Number") shouldEqual in.properties("Number")
            out.getBooleanProperty("IsOdd") shouldEqual in.properties("IsOdd")
            out.getBooleanProperty("IsEven") shouldEqual in.properties("IsEven")
            // Make sure we are only receiving odd numbers
            out.getIntProperty("Number") % 2 shouldEqual 1
        }
    }

    "applying backpressure when the consumer is slower than the producer" in withServer() { ctx =>
      val connectionFactory = new ActiveMQConnectionFactory(ctx.url)
      val in = 0 to 25 map (i => ('a' + i).asInstanceOf[Char].toString)
      Source(in).runWith(JmsProducer.textSink(JmsProducerSettings(connectionFactory).withQueue("test")))

      val jmsSource: Source[AckEnvelope, KillSwitch] = JmsConsumer.ackSource(
        JmsConsumerSettings(connectionFactory).withSessionCount(5).withBufferSize(5).withQueue("test")
      )

      val result = jmsSource
        .throttle(10, 1.second, 1, ThrottleMode.shaping)
        .take(in.size)
        .map(env => (env, env.message.asInstanceOf[TextMessage].getText))
        .map { case (env, text) => env.acknowledge(); text }
        .runWith(Sink.seq)

      result.futureValue should contain theSameElementsAs in
    }

    "disconnection should fail the stage" in withServer() { ctx =>
      val connectionFactory = new ActiveMQConnectionFactory(ctx.url)
      val result = JmsConsumer.ackSource(JmsConsumerSettings(connectionFactory).withQueue("test")).runWith(Sink.seq)
      Thread.sleep(500)
      ctx.broker.stop()
      result.failed.futureValue shouldBe an[JMSException]
    }

    "publish and consume elements through a topic " in withServer() { ctx =>
      import system.dispatcher

      val connectionFactory = new ActiveMQConnectionFactory(ctx.url)

      val jmsTopicSink: Sink[String, Future[Done]] = JmsProducer.textSink(
        JmsProducerSettings(connectionFactory).withTopic("topic")
      )
      val jmsTopicSink2: Sink[String, Future[Done]] = JmsProducer.textSink(
        JmsProducerSettings(connectionFactory).withTopic("topic")
      )

      val in = List("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")
      val inNumbers = (1 to 10).map(_.toString)

      val jmsTopicSource: Source[AckEnvelope, KillSwitch] = JmsConsumer.ackSource(
        JmsConsumerSettings(connectionFactory).withSessionCount(1).withBufferSize(5).withTopic("topic")
      )
      val jmsSource2: Source[AckEnvelope, KillSwitch] = JmsConsumer.ackSource(
        JmsConsumerSettings(connectionFactory).withSessionCount(1).withBufferSize(5).withTopic("topic")
      )

      val expectedSize = in.size + inNumbers.size
      val result1 = jmsTopicSource
        .take(expectedSize)
        .map(env => (env, env.message.asInstanceOf[TextMessage].getText))
        .map { case (env, text) => env.acknowledge(); text }
        .runWith(Sink.seq)
        .map(_.sorted)
      val result2 = jmsSource2
        .take(expectedSize)
        .map(env => (env, env.message.asInstanceOf[TextMessage].getText))
        .map { case (env, text) => env.acknowledge(); text }
        .runWith(Sink.seq)
        .map(_.sorted)

      //We wait a little to be sure that the source is connected
      Thread.sleep(500)

      Source(in).runWith(jmsTopicSink)
      Source(inNumbers).runWith(jmsTopicSink2)

      val expectedList: List[String] = in ++ inNumbers
      result1.futureValue should contain theSameElementsAs expectedList
      result2.futureValue should contain theSameElementsAs expectedList
    }

    "ensure no message loss when stopping a stream" in withServer() { ctx =>
      val connectionFactory = new ActiveMQConnectionFactory(ctx.url)

      val jmsSink: Sink[JmsTextMessage, Future[Done]] = JmsProducer(
        JmsProducerSettings(connectionFactory).withQueue("numbers")
      )

      val (publishKillSwitch, publishedData) = Source
        .unfold(1)(n => Some(n + 1 -> n))
        .throttle(15, 1.second, 2, ThrottleMode.shaping) // Higher than consumption rate.
        .viaMat(KillSwitches.single)(Keep.right)
        .alsoTo(Flow[Int].map(n => JmsTextMessage(n.toString).withProperty("Number", n)).to(jmsSink))
        .toMat(Sink.seq)(Keep.both)
        .run()

      val jmsSource: Source[AckEnvelope, KillSwitch] = JmsConsumer.ackSource(
        JmsConsumerSettings(connectionFactory).withSessionCount(5).withBufferSize(5).withQueue("numbers")
      )

      val resultQueue = new LinkedBlockingQueue[String]()

      val (killSwitch, streamDone) = jmsSource
        .throttle(10, 1.second, 2, ThrottleMode.shaping)
        .toMat(
          Sink.foreach { env =>
            resultQueue.add(env.message.asInstanceOf[TextMessage].getText)
            env.acknowledge()
          }
        )(Keep.both)
        .run()

      // Need to wait for the stream to have started and running for sometime
      Thread.sleep(2000)

      killSwitch.shutdown()

      streamDone.futureValue shouldBe Done

      // Keep publishing for another 2 seconds to make sure we killed the consumption mid-stream.
      Thread.sleep(2000)

      publishKillSwitch.shutdown()
      val numsIn = publishedData.futureValue

      resultQueue.size should be < numsIn.size

      val killSwitch2 = jmsSource
        .to(
          Sink.foreach { env =>
            resultQueue.add(env.message.asInstanceOf[TextMessage].getText)
            env.acknowledge()
          }
        )
        .run()

      val resultList = new mutable.ArrayBuffer[String](numsIn.size)

      @tailrec
      def keepPolling(): Unit =
        Option(resultQueue.poll(2, TimeUnit.SECONDS)) match {
          case Some(entry) =>
            resultList += entry
            keepPolling()
          case None =>
        }

      keepPolling()

      killSwitch2.shutdown()

      implicit val _ = Ordering.by { s: String =>
        s.toInt
      }
      resultList.to[SortedSet] should contain theSameElementsAs numsIn.map(_.toString)
    }

    "ensure no message loss when aborting a stream" in withServer() { ctx =>
      val connectionFactory = new ActiveMQConnectionFactory(ctx.url)

      val jmsSink: Sink[JmsTextMessage, Future[Done]] = JmsProducer(
        JmsProducerSettings(connectionFactory).withQueue("numbers")
      )

      val (publishKillSwitch, publishedData) = Source
        .unfold(1)(n => Some(n + 1 -> n))
        .throttle(15, 1.second, 2, ThrottleMode.shaping) // Higher than consumption rate.
        .viaMat(KillSwitches.single)(Keep.right)
        .alsoTo(Flow[Int].map(n => JmsTextMessage(n.toString).withProperty("Number", n)).to(jmsSink))
        .toMat(Sink.seq)(Keep.both)
        .run()

      // We need this ack mode for AMQ to not lose messages as ack normally acks any messages read on the session.
      val individualAck = new AcknowledgeMode(ActiveMQSession.INDIVIDUAL_ACKNOWLEDGE)

      val jmsSource: Source[AckEnvelope, KillSwitch] = JmsConsumer.ackSource(
        JmsConsumerSettings(connectionFactory)
          .withSessionCount(5)
          .withBufferSize(5)
          .withQueue("numbers")
          .withAcknowledgeMode(individualAck)
      )

      val resultQueue = new LinkedBlockingQueue[String]()

      val (killSwitch, streamDone) = jmsSource
        .throttle(10, 1.second, 2, ThrottleMode.shaping)
        .toMat(
          Sink.foreach { env =>
            resultQueue.add(env.message.asInstanceOf[TextMessage].getText)
            env.acknowledge()
          }
        )(Keep.both)
        .run()

      // Need to wait for the stream to have started and running for sometime.
      Thread.sleep(2000)

      val ex = new Exception("Test exception")
      killSwitch.abort(ex)

      import system.dispatcher
      val resultTry = streamDone.map(Success(_)).recover { case e => Failure(e) }.futureValue

      // Keep publishing for another 2 seconds to make sure we killed the consumption mid-stream.
      Thread.sleep(2000)

      publishKillSwitch.shutdown()
      val numsIn = publishedData.futureValue

      // Ensure we break the stream while reading, not all input should have been read.
      resultQueue.size should be < numsIn.size
      resultTry shouldBe Failure(ex)

      val killSwitch2 = jmsSource
        .to(
          Sink.foreach { env =>
            resultQueue.add(env.message.asInstanceOf[TextMessage].getText)
            env.acknowledge()
          }
        )
        .run()

      val resultList = new mutable.ArrayBuffer[String](numsIn.size)

      @tailrec
      def keepPolling(): Unit =
        Option(resultQueue.poll(2, TimeUnit.SECONDS)) match {
          case Some(entry) =>
            resultList += entry
            keepPolling()
          case None =>
        }

      keepPolling()

      killSwitch2.shutdown()

      implicit val _ = Ordering.by { s: String =>
        s.toInt
      }
      resultList.to[SortedSet] should contain theSameElementsAs numsIn.map(_.toString)
    }
  }
}
