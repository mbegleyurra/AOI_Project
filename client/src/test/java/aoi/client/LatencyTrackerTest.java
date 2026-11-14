package aoi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LatencyTrackerTest {
    private static final int SELF = 1;
    private static final int OTHER = 2;
    private static final long TICK_NS = 50_000_000L;
    private static final float RADIUS = 100f;
    private static final float NEAR = 50f;
    private static final float FAR = 500f;

    private static void spec(LatencyTracker t, int tick, float otherX, long nanos) {
        t.onSpectator(tick, new int[] {SELF, OTHER}, new float[] {0, otherX}, new float[] {0, 0}, nanos);
    }

    /**
     * Drives both streams tick by tick (spectator first, then player, both stamped tick*50ms; the player stamp gets
     * extraNs on top, like --net-delay-ms). OTHER is geometrically visible from firstNear on, and appears in the
     * player's snapshots from knownFrom on.
     */
    private static LatencyTracker run(int warmup, int firstNear, int knownFrom, int lastTick, long extraNs) {
        LatencyTracker t = new LatencyTracker(SELF, RADIUS, warmup, false);
        for (int tick = 1; tick <= lastTick; tick++) {
            spec(t, tick, tick >= firstNear ? NEAR : FAR, tick * TICK_NS);
            int[] ids = tick >= knownFrom ? new int[] {SELF, OTHER} : new int[] {SELF};
            t.onPlayer(tick, ids, tick * TICK_NS + extraNs);
        }
        return t;
    }

    private static void assertClean(List<Double> values) {
        for (double v : values) {
            assertFalse(Double.isNaN(v), "NaN in " + values);
            assertFalse(Double.isInfinite(v), "infinite in " + values);
            assertTrue(v >= 0, "negative in " + values);
        }
    }

    @Test
    void baselineAllKnownFromFirstSnapshotIsZeroAndPreknown() {
        LatencyTracker t = run(2, 6, 1, 12, 0);
        assertEquals(List.of(0.0), t.delayMs());
        assertEquals(List.of(0.0), t.delayTicks());
        assertEquals(1, t.preknown());
        assertEquals(0, t.lostBeforeKnown());
        assertEquals(0, t.pendingCount());
    }

    @Test
    void baselineStaysZeroUnderNetDelay() {
        LatencyTracker t = run(2, 6, 1, 12, 80_000_000L);
        assertEquals(List.of(0.0), t.delayMs());
        assertEquals(1, t.preknown());
    }

    @Test
    void aoiDelayIsTimeFromGeometricVisibilityToSnapshot() {
        // visible at tick 6, delivered in the tick 8 snapshot -> 2 ticks = 100 ms
        LatencyTracker t = run(2, 6, 8, 12, 0);
        assertEquals(1, t.delayMs().size());
        assertEquals(100.0, t.delayMs().get(0), 1e-9);
        assertEquals(List.of(2.0), t.delayTicks());
        assertEquals(0, t.preknown());
        assertEquals(0, t.pendingCount());
    }

    @Test
    void aoiEntityDeliveredOnSameTickHasZeroDelayButIsNotPreknown() {
        LatencyTracker t = run(2, 6, 6, 12, 0);
        assertEquals(List.of(0.0), t.delayMs());
        assertEquals(List.of(0.0), t.delayTicks());
        assertEquals(0, t.preknown());
    }

    @Test
    void netDelayAddsToAoiDelayButNotToTickCount() {
        LatencyTracker plain = run(2, 6, 8, 12, 0);
        LatencyTracker slow = run(2, 6, 8, 12, 80_000_000L);
        assertEquals(100.0, plain.delayMs().get(0), 1e-9);
        assertEquals(180.0, slow.delayMs().get(0), 1e-9);
        assertEquals(List.of(2.0), slow.delayTicks());
    }

    @Test
    void entityBecomingVisibleDuringWarmupIsNotCounted() {
        // warmup ends at tick 1 + 10 = 11; visible from tick 4, delivered at tick 6
        LatencyTracker t = run(10, 4, 6, 20, 0);
        assertTrue(t.delayMs().isEmpty());
        assertEquals(0, t.preknown());
        assertEquals(0, t.pendingCount());
        assertTrue(t.warmedUp());
    }

    @Test
    void entityAlreadyVisibleAtEndOfWarmupIsNeverNew() {
        LatencyTracker t = run(10, 3, 15, 25, 0);
        assertTrue(t.delayMs().isEmpty());
        assertEquals(0, t.pendingCount());
    }

    @Test
    void warmupBoundaryFirstMeasuredTickCountsLastWarmupTickDoesNot() {
        LatencyTracker atBoundary = run(10, 12, 14, 25, 0); // tick 12 > 11 -> measured
        assertEquals(1, atBoundary.delayMs().size());
        assertEquals(100.0, atBoundary.delayMs().get(0), 1e-9);
        LatencyTracker inWarmup = run(10, 11, 14, 25, 0); // tick 11 is still warm-up
        assertTrue(inWarmup.delayMs().isEmpty());
    }

    @Test
    void nothingIsMeasuredUntilBothStreamsDelivered() {
        LatencyTracker t = new LatencyTracker(SELF, RADIUS, 0, false);
        for (int tick = 1; tick <= 10; tick++) {
            spec(t, tick, tick >= 3 ? NEAR : FAR, tick * TICK_NS);
        }
        assertFalse(t.warmedUp());
        assertTrue(t.delayMs().isEmpty());
        assertEquals(0, t.pendingCount());
    }

    @Test
    void playerClockBehindSpectatorClockNeverGivesNegativeDelay() {
        LatencyTracker t = new LatencyTracker(SELF, RADIUS, 0, false);
        for (int tick = 1; tick <= 5; tick++) {
            spec(t, tick, FAR, 1_000_000_000L + tick * TICK_NS);
            t.onPlayer(tick, new int[] {SELF}, 1_000_000_000L + tick * TICK_NS);
        }
        spec(t, 6, NEAR, 2_000_000_000L);
        // delivered with a stamp EARLIER than the spectator stamp (receipt-order jitter)
        t.onPlayer(6, new int[] {SELF, OTHER}, 1_900_000_000L);
        assertEquals(List.of(0.0), t.delayMs());
        assertClean(t.delayMs());
        assertClean(t.delayTicks());
    }

    @Test
    void delaysAreAlwaysFiniteAndNonNegativeOverManyEntities() {
        LatencyTracker t = new LatencyTracker(SELF, RADIUS, 2, false);
        int n = 20;
        for (int tick = 1; tick <= 200; tick++) {
            int[] ids = new int[n + 1];
            float[] xs = new float[n + 1];
            float[] ys = new float[n + 1];
            ids[0] = SELF;
            for (int i = 1; i <= n; i++) {
                ids[i] = SELF + i;
                xs[i] = (float) (RADIUS * 0.9 * Math.sin(tick / 7.0 + i));
            }
            t.onSpectator(tick, ids, xs, ys, tick * TICK_NS);
            // AOI-style: the player sees what was geometrically visible two ticks ago, approximated by a lagged set
            int[] seen = new int[n + 1];
            seen[0] = SELF;
            int k = 1;
            for (int i = 1; i <= n; i++) {
                float lagged = (float) (RADIUS * 0.9 * Math.sin((tick - 2) / 7.0 + i));
                if (Math.abs(lagged) <= RADIUS) {
                    seen[k++] = SELF + i;
                }
            }
            t.onPlayer(tick, java.util.Arrays.copyOf(seen, k), tick * TICK_NS);
        }
        assertClean(t.delayMs());
        assertClean(t.delayTicks());
        assertEquals(t.delayMs().size(), t.delayTicks().size());
    }

    @Test
    void entityThatLeavesViewBeforeDeliveryIsCountedAsLostNotAsDelay() {
        LatencyTracker t = new LatencyTracker(SELF, RADIUS, 0, false);
        for (int tick = 1; tick <= 10; tick++) {
            spec(t, tick, tick == 5 || tick == 6 ? NEAR : FAR, tick * TICK_NS);
            t.onPlayer(tick, new int[] {SELF}, tick * TICK_NS);
        }
        assertTrue(t.delayMs().isEmpty());
        assertEquals(1, t.lostBeforeKnown());
        assertEquals(0, t.pendingCount());
    }

    @Test
    void duplicateOrOutOfOrderSpectatorTicksAreIgnored() {
        LatencyTracker t = new LatencyTracker(SELF, RADIUS, 0, false);
        spec(t, 5, FAR, 5 * TICK_NS);
        spec(t, 5, FAR, 5 * TICK_NS);
        spec(t, 4, FAR, 4 * TICK_NS);
        assertEquals(1, t.spectatorSnapshots());
    }

    @Test
    void lineOfSightFiltersEntitiesBehindAnObstacle() {
        LatencyTracker t = new LatencyTracker(SELF, RADIUS, 0, true);
        t.setRects(new float[] {20, -5, 10, 10}); // wall between self (0,0) and OTHER (50,0)
        for (int tick = 1; tick <= 10; tick++) {
            spec(t, tick, NEAR, tick * TICK_NS);
            t.onPlayer(tick, new int[] {SELF}, tick * TICK_NS);
        }
        assertTrue(t.delayMs().isEmpty());
        assertEquals(0, t.pendingCount());
        assertEquals(0, t.lostBeforeKnown());
    }

    @Test
    void segmentBlockedDetectsCrossingAndMiss() {
        float[] rects = {10, -5, 10, 10};
        assertTrue(LatencyTracker.segmentBlocked(rects, 0, 0, 30, 0));
        assertFalse(LatencyTracker.segmentBlocked(rects, 0, 20, 30, 20));
        assertFalse(LatencyTracker.segmentBlocked(rects, 0, 0, 5, 0));
    }

    @Test
    void statsComputeMeanMedianP95Max() {
        LatencyTracker.Stats s = new LatencyTracker.Stats(List.of(4.0, 1.0, 3.0, 2.0));
        assertEquals(4, s.count);
        assertEquals(2.5, s.mean, 1e-9);
        assertEquals(2.5, s.median, 1e-9);
        assertEquals(4.0, s.p95, 1e-9);
        assertEquals(4.0, s.max, 1e-9);
    }

    @Test
    void statsOfNothingAreNaNWithZeroCountAndCsvRowsStayWellFormed() {
        LatencyTracker.Stats s = new LatencyTracker.Stats(List.of());
        assertEquals(0, s.count);
        assertTrue(Double.isNaN(s.mean));
        LatencyTracker t = run(2, 6, 1, 12, 0);
        List<String> rows = t.csvRows("baseline");
        assertEquals(7, rows.size());
        for (String r : rows) {
            assertEquals(7, r.split(",").length, r);
        }
        assertTrue(rows.get(0).startsWith("baseline,new_entity_delay_ms,1,0.000,0.000,0.000,0.000"), rows.get(0));
    }
}
