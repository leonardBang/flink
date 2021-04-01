/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.runtime.stream.sql

import org.apache.flink.api.scala._
import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.runtime.utils.JavaUserDefinedScalarFunctions.JavaFunc5
import org.apache.flink.table.planner.runtime.utils.{StreamingTestBase, TestingAppendSink}
import org.apache.flink.types.Row
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import java.sql.Timestamp
import java.time.{Instant, LocalDateTime}
import java.util.TimeZone

/**
  * Integration tests for time attributes defined in DDL.
  */
class TimeAttributeITCase extends StreamingTestBase {

  val data = List(
    rowOf("1970-01-01 00:00:00.001", localDateTime(1L), 1, 1d),
    rowOf("1970-01-01 00:00:00.002", localDateTime(2L), 1, 2d),
    rowOf("1970-01-01 00:00:00.003", localDateTime(3L), 1, 2d),
    rowOf("1970-01-01 00:00:00.004", localDateTime(4L), 1, 5d),
    rowOf("1970-01-01 00:00:00.007", localDateTime(7L), 1, 3d),
    rowOf("1970-01-01 00:00:00.008", localDateTime(8L), 1, 3d),
    rowOf("1970-01-01 00:00:00.016", localDateTime(16L), 1, 4d))

  val dataId: String = TestValuesTableFactory.registerData(data)

  val ltzData = List(
    rowOf("1970-01-01 08:00:00.001", Instant.ofEpochSecond(1), 1, 1d),
    rowOf("1970-01-01 08:00:00.002", Instant.ofEpochSecond(2L), 1, 2d),
    rowOf("1970-01-01 08:00:00.003", Instant.ofEpochSecond(3L), 1, 2d),
    rowOf("1970-01-01 08:00:00.004", Instant.ofEpochSecond(4L), 1, 5d),
    rowOf("1970-01-01 08:00:00.007", Instant.ofEpochSecond(7L), 1, 3d),
    rowOf("1970-01-01 08:00:00.008", Instant.ofEpochSecond(8L), 1, 3d),
    rowOf("1970-01-01 08:00:00.016", Instant.ofEpochSecond(16L), 1, 4d))

  val ltzDataId: String = TestValuesTableFactory.registerData(ltzData)


  @Test
  def testWindowAggregateOnWatermark(): Unit = {
    val ddl =
      s"""
         |CREATE TABLE src (
         |  log_ts STRING,
         |  ts TIMESTAMP_LTZ(3),
         |  a INT,
         |  b DOUBLE,
         |  WATERMARK FOR ts AS ts - INTERVAL '0.001' SECOND
         |) WITH (
         |  'connector' = 'values',
         |  'data-id' = '$ltzDataId'
         |)
      """.stripMargin
    val query =
      """
        |SELECT TUMBLE_END(ts, INTERVAL '0.003' SECOND), COUNT(ts), SUM(b)
        |FROM src
        |GROUP BY TUMBLE(ts, INTERVAL '0.003' SECOND)
      """.stripMargin
    tEnv.executeSql(ddl)
    val sink = new TestingAppendSink()
    tEnv.sqlQuery(query).toAppendStream[Row].addSink(sink)
    env.execute("SQL JOB")

    val expected = Seq(
      "1970-01-01T00:00:00.003,2,3.0",
      "1970-01-01T00:00:00.006,2,7.0",
      "1970-01-01T00:00:00.009,2,6.0",
      "1970-01-01T00:00:00.018,1,4.0")
    assertEquals(expected.sorted, sink.getAppendResults.sorted)
  }

  @Test
  def testWindowAggregateOnCustomizedWatermark(): Unit = {
    JavaFunc5.openCalled = false
    JavaFunc5.closeCalled = false
    tEnv.createTemporaryFunction("myFunc", new JavaFunc5)
    val ddl =
      s"""
        |CREATE TABLE src (
        |  log_ts STRING,
        |  ts TIMESTAMP(3),
        |  a INT,
        |  b DOUBLE,
        |  WATERMARK FOR ts AS myFunc(ts, a)
        |) WITH (
        |  'connector' = 'values',
        |  'data-id' = '$dataId'
        |)
      """.stripMargin
    val query =
      """
        |SELECT TUMBLE_END(ts, INTERVAL '0.003' SECOND), COUNT(ts), SUM(b)
        |FROM src
        |GROUP BY TUMBLE(ts, INTERVAL '0.003' SECOND)
      """.stripMargin
    tEnv.executeSql(ddl)
    val sink = new TestingAppendSink()
    tEnv.sqlQuery(query).toAppendStream[Row].addSink(sink)
    env.execute("SQL JOB")

    val expected = Seq(
      "1970-01-01T00:00:00.003,2,3.0",
      "1970-01-01T00:00:00.006,2,7.0",
      "1970-01-01T00:00:00.009,2,6.0",
      "1970-01-01T00:00:00.018,1,4.0")
    assertEquals(expected.sorted, sink.getAppendResults.sorted)
    assertTrue(JavaFunc5.openCalled)
    assertTrue(JavaFunc5.closeCalled)
  }

  @Test
  def testWindowAggregateOnComputedRowtime(): Unit = {
    val ddl =
      s"""
        |CREATE TABLE src (
        |  log_ts STRING,
        |  ts TIMESTAMP(3),
        |  a INT,
        |  b DOUBLE,
        |  rowtime AS CAST(log_ts AS TIMESTAMP(3)),
        |  WATERMARK FOR rowtime AS rowtime - INTERVAL '0.001' SECOND
        |) WITH (
        |  'connector' = 'values',
        |  'data-id' = '$dataId'
        |)
      """.stripMargin
    val query =
      """
        |SELECT TUMBLE_END(rowtime, INTERVAL '0.003' SECOND), COUNT(ts), SUM(b)
        |FROM src
        |GROUP BY TUMBLE(rowtime, INTERVAL '0.003' SECOND)
      """.stripMargin
    tEnv.executeSql(ddl)
    val sink = new TestingAppendSink()
    tEnv.sqlQuery(query).toAppendStream[Row].addSink(sink)
    env.execute("SQL JOB")

    val expected = Seq(
      "1970-01-01T00:00:00.003,2,3.0",
      "1970-01-01T00:00:00.006,2,7.0",
      "1970-01-01T00:00:00.009,2,6.0",
      "1970-01-01T00:00:00.018,1,4.0")
    assertEquals(expected.sorted, sink.getAppendResults.sorted)
  }

  // ------------------------------------------------------------------------------------------

  private def localDateTime(ts: Long): LocalDateTime = {
    new Timestamp(ts - TimeZone.getDefault.getOffset(ts)).toLocalDateTime
  }

}
