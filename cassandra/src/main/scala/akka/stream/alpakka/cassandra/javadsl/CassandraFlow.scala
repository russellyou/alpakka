/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.cassandra.javadsl

import java.util.function.BiFunction
import java.util.function.Function

import akka.NotUsed
import akka.stream.alpakka.cassandra.CassandraBatchSettings
import com.datastax.driver.core.{BoundStatement, PreparedStatement, Session}
import akka.stream.alpakka.cassandra.scaladsl.{CassandraFlow => ScalaCFlow}
import akka.stream.javadsl.Flow

import scala.concurrent.ExecutionContext

object CassandraFlow {
  def createWithPassThrough[T](parallelism: Int,
                               statement: PreparedStatement,
                               statementBinder: BiFunction[T, PreparedStatement, BoundStatement],
                               session: Session,
                               ec: ExecutionContext): Flow[T, T, NotUsed] =
    ScalaCFlow
      .createWithPassThrough[T](parallelism, statement, (t, p) => statementBinder.apply(t, p))(session, ec)
      .asJava

  /**
   * Creates a flow that batches using an unlogged batch. Use this when most of the elements in the stream
   * share the same partition key. Cassandra unlogged batches that share the same partition key will only
   * resolve to one write internally in Cassandra, boosting write performance.
   *
   * Be aware that this stage does not preserve the upstream order.
   */
  def createUnloggedBatchWithPassThrough[T, K](parallelism: Int,
                                               statement: PreparedStatement,
                                               statementBinder: BiFunction[T, PreparedStatement, BoundStatement],
                                               partitionKey: Function[T, K],
                                               settings: CassandraBatchSettings = CassandraBatchSettings.Defaults,
                                               session: Session,
                                               ec: ExecutionContext): Flow[T, T, NotUsed] =
    ScalaCFlow
      .createUnloggedBatchWithPassThrough[T, K](parallelism,
                                                statement,
                                                (t, p) => statementBinder.apply(t, p),
                                                t => partitionKey.apply(t),
                                                settings)(session, ec)
      .asJava
}
