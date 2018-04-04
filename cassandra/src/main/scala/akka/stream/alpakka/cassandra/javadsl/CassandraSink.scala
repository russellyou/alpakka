/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.cassandra.javadsl

import java.util.concurrent.CompletionStage
import java.util.function.BiFunction

import akka.Done
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSink => ScalaCSink}
import akka.stream.javadsl.Sink
import com.datastax.driver.core.{BoundStatement, PreparedStatement, Session}

import scala.compat.java8.FutureConverters._

object CassandraSink {

  def create[T](parallelism: Int,
                statement: PreparedStatement,
                statementBinder: BiFunction[T, PreparedStatement, BoundStatement],
                session: Session): Sink[T, CompletionStage[Done]] = {
    val sink =
      ScalaCSink.apply[T](parallelism, statement, (t, p) => statementBinder.apply(t, p))(session)

    sink.mapMaterializedValue(_.toJava).asJava
  }
}
