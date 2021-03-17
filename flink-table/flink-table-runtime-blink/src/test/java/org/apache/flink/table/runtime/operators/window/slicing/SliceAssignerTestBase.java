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

package org.apache.flink.table.runtime.operators.window.slicing;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.data.writer.BinaryRowWriter;

import org.apache.flink.shaded.guava18.com.google.common.collect.Lists;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;

import static org.apache.flink.core.testutils.FlinkMatchers.containsMessage;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/** Utilities for testing {@link SliceAssigner}s. */
public abstract class SliceAssignerTestBase {

    private static final ClockService CLOCK_SERVICE = System::currentTimeMillis;

    protected static void assertErrorMessage(Runnable runnable, String errorMessage) {
        try {
            runnable.run();
            fail("should fail.");
        } catch (Exception e) {
            assertThat(e, containsMessage(errorMessage));
        }
    }

    protected static long assignSliceEnd(SliceAssigner assigner, long timestamp) {
        return assigner.assignSliceEnd(row(timestamp), CLOCK_SERVICE);
    }

    protected static List<Long> expiredSlices(SliceAssigner assigner, long sliceEnd) {
        return Lists.newArrayList(assigner.expiredSlices(sliceEnd));
    }

    protected static Long mergeResultSlice(SliceSharedAssigner assigner, long sliceEnd)
            throws Exception {
        TestingMergingCallBack callBack = new TestingMergingCallBack();
        assigner.mergeSlices(sliceEnd, callBack);
        return callBack.mergeResult;
    }

    protected static List<Long> toBeMergedSlices(SliceSharedAssigner assigner, long sliceEnd)
            throws Exception {
        TestingMergingCallBack callBack = new TestingMergingCallBack();
        assigner.mergeSlices(sliceEnd, callBack);
        return callBack.toBeMerged;
    }

    protected static RowData row(long timestamp) {
        BinaryRowData binaryRowData = new BinaryRowData(1);
        BinaryRowWriter writer = new BinaryRowWriter(binaryRowData);
        writer.writeTimestamp(0, TimestampData.fromEpochMillis(timestamp), 3);
        writer.complete();
        return binaryRowData;
    }

    private static final class TestingMergingCallBack implements SliceSharedAssigner.MergeCallback {

        private Long mergeResult;
        private List<Long> toBeMerged;

        @Override
        public void merge(Long mergeResult, Iterable<Long> toBeMerged) throws Exception {
            this.mergeResult = mergeResult;
            this.toBeMerged = Lists.newArrayList(toBeMerged);
        }
    }

    /** Get epoch mills from a timestamp string and the time zone the timestamp belonged to. */
    protected static long epochMills(TimeZone timeZone, String timestampStr) {
        LocalDateTime localDateTime = LocalDateTime.parse(timestampStr);
        ZoneOffset zoneOffset = timeZone.toZoneId().getRules().getOffset(localDateTime);
        return localDateTime.toInstant(zoneOffset).toEpochMilli();
    }
}
