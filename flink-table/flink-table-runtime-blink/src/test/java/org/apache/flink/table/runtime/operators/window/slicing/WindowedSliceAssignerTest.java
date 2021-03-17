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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Tests for {@link SliceAssigners.WindowedSliceAssigner}. */
@RunWith(Parameterized.class)
public class WindowedSliceAssignerTest extends SliceAssignerTestBase {

    @Parameterized.Parameter public TimeZone timeZone;

    @Parameterized.Parameters(name = "timezone = {0}")
    public static Collection<TimeZone> parameters() {
        return Arrays.asList(TimeZone.getTimeZone("UTC"), TimeZone.getTimeZone("Asia/Shanghai"));
    }

    private SliceAssigner TUMBLE_ASSIGNER;
    private SliceAssigner HOP_ASSIGNER;
    private SliceAssigner CUMULATE_ASSIGNER;

    @Before
    public void setUp() {
        this.TUMBLE_ASSIGNER =
                SliceAssigners.tumbling(-1, timeZone.getRawOffset(), Duration.ofHours(5));
        this.HOP_ASSIGNER =
                SliceAssigners.hopping(
                        0, timeZone.getRawOffset(), Duration.ofHours(5), Duration.ofHours(1));
        this.CUMULATE_ASSIGNER =
                SliceAssigners.cumulative(
                        0, timeZone.getRawOffset(), Duration.ofHours(5), Duration.ofHours(1));
    }

    @Test
    public void testSliceAssignment() {
        SliceAssigner assigner = SliceAssigners.windowed(0, TUMBLE_ASSIGNER);

        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assignSliceEnd(assigner, epochMills("1970-01-01T00:00:00")));
        assertEquals(
                epochMills("1970-01-01T05:00:00"),
                assignSliceEnd(assigner, epochMills("1970-01-01T05:00:00")));
        assertEquals(
                epochMills("1970-01-01T10:00:00"),
                assignSliceEnd(assigner, epochMills("1970-01-01T10:00:00")));
    }

    @Test
    public void testGetWindowStartForTumble() {
        SliceAssigner assigner = SliceAssigners.windowed(0, TUMBLE_ASSIGNER);

        assertEquals(
                epochMills("1969-12-31T19:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T00:00:00")));
        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T05:00:00")));
        assertEquals(
                epochMills("1970-01-01T05:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T10:00:00")));
    }

    @Test
    public void testGetWindowStartForHop() {
        SliceAssigner assigner = SliceAssigners.windowed(0, HOP_ASSIGNER);

        assertEquals(
                epochMills("1969-12-31T19:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T00:00:00")));
        assertEquals(
                epochMills("1969-12-31T20:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T01:00:00")));
        assertEquals(
                epochMills("1969-12-31T21:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T02:00:00")));
        assertEquals(
                epochMills("1969-12-31T22:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T03:00:00")));
        assertEquals(
                epochMills("1969-12-31T23:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T04:00:00")));
        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T05:00:00")));
        assertEquals(
                epochMills("1970-01-01T01:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T06:00:00")));
        assertEquals(
                epochMills("1970-01-01T05:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T10:00:00")));
    }

    @Test
    public void testGetWindowStartForCumulate() {
        SliceAssigner assigner = SliceAssigners.windowed(0, CUMULATE_ASSIGNER);

        assertEquals(
                epochMills("1969-12-31T19:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T00:00:00")));
        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T01:00:00")));
        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T02:00:00")));
        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T03:00:00")));
        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T04:00:00")));
        assertEquals(
                epochMills("1970-01-01T00:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T05:00:00")));
        assertEquals(
                epochMills("1970-01-01T05:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T06:00:00")));
        assertEquals(
                epochMills("1970-01-01T05:00:00"),
                assigner.getWindowStart(epochMills("1970-01-01T10:00:00")));
    }

    @Test
    public void testExpiredSlices() {
        SliceAssigner assigner = SliceAssigners.windowed(0, TUMBLE_ASSIGNER);

        assertEquals(
                Collections.singletonList(epochMills("1970-01-01T00:00:00")),
                expiredSlices(assigner, epochMills("1970-01-01T00:00:00")));
        assertEquals(
                Collections.singletonList(epochMills("1970-01-01T05:00:00")),
                expiredSlices(assigner, epochMills("1970-01-01T05:00:00")));
        assertEquals(
                Collections.singletonList(epochMills("1970-01-01T10:00:00")),
                expiredSlices(assigner, epochMills("1970-01-01T10:00:00")));
    }

    @Test
    public void testEventTime() {
        SliceAssigner assigner = SliceAssigners.windowed(0, TUMBLE_ASSIGNER);
        assertTrue(assigner.isEventTime());
    }

    @Test
    public void testInvalidParameters() {
        assertErrorMessage(
                () -> SliceAssigners.windowed(-1, TUMBLE_ASSIGNER),
                "Windowed slice assigner must have a positive window end index.");

        // should pass
        SliceAssigners.windowed(1, TUMBLE_ASSIGNER);
    }

    /** Get epoch mills from a timestamp string and the parameterized time zone. */
    private long epochMills(String timestampStr) {
        return epochMills(timeZone, timestampStr);
    }
}
