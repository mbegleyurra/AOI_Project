package aoi.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Socket-free metric and matching logic for {@link LatencyProbe}.
 *
 * Ground truth is geometric, taken from the spectator's unfiltered world feed: an entity "becomes visible" on the
 * first spectator tick where it is within {@code viewRadius} of the probe player (and, when {@code los} is on and
 * obstacle rects are known, no obstacle rectangle blocks the straight line between them) after not being so on the
 * previous spectator tick.
 *
 * "Time to know" = receipt time of the first player snapshot that contains that entity minus the receipt time of
 * the spectator tick in which it became visible. An entity the player already held (in a snapshot of an EARLIER
 * tick) when it became visible has delay 0 and is counted as preknown; with the server's --algo=all baseline that
 * is every entity.
 *
 * Warm-up: nothing is measured until both streams have delivered a snapshot, and then the first
 * {@code warmupTicks} ticks (counted from the later of the two first ticks) are ignored. Entities already visible
 * at the end of warm-up are never counted as new.
 *
 * Times are System.nanoTime() values supplied by the caller, so tests can drive the tracker deterministically.
 * Thread-safe (all public methods synchronized).
 */
public final class LatencyTracker {
    private final int selfId;
    private final float viewRadius;
    private final int warmupTicks;
    private final boolean los;
    private float[] rects;

    // stream state
    private int firstSpecTick = -1;
    private int firstPlayerTick = -1;
    private long warmupEndTick = Long.MAX_VALUE;
    private int lastSpecTick = Integer.MIN_VALUE;
    private Set<Integer> prevVisible;
    /** entityId -> tick of the first snapshot in its current unbroken run of appearing in player snapshots. */
    private Map<Integer, Integer> knownSince = new HashMap<>();
    /** entityId -> {specNanos, tick} for entities visible in the ground truth that the player does not hold yet. */
    private final Map<Integer, long[]> pending = new HashMap<>();
    private final Map<Integer, Long> specNanosByTick = new HashMap<>();
    private final Map<Integer, Long> playerNanosByTick = new HashMap<>();
    private long lastPlayerNanos = -1;

    // results
    private final List<Double> delayMs = new ArrayList<>();
    private final List<Double> delayTicks = new ArrayList<>();
    private final List<Double> skewMs = new ArrayList<>();
    private final List<Double> intervalMs = new ArrayList<>();
    private final List<Double> snapLatencyMs = new ArrayList<>();
    private int preknown;
    private int lostBeforeKnown;
    private int playerSnaps;
    private int specSnaps;

    public LatencyTracker(int selfId, float viewRadius, int warmupTicks, boolean los) {
        this.selfId = selfId;
        this.viewRadius = viewRadius;
        this.warmupTicks = Math.max(0, warmupTicks);
        this.los = los;
    }

    /** Obstacle rectangles x,y,w,h per obstacle (world units); only used when los is on. */
    public synchronized void setRects(float[] r) {
        this.rects = r;
    }

    public synchronized void onSpectator(int tick, int[] ids, float[] xs, float[] ys, long nanos) {
        if (tick <= lastSpecTick) {
            return;
        }
        lastSpecTick = tick;
        specSnaps++;
        if (firstSpecTick < 0) {
            firstSpecTick = tick;
        }
        updateWarmup();
        boolean measuring = tick > warmupEndTick;
        if (measuring) {
            Long p = playerNanosByTick.remove(tick);
            if (p != null) {
                skewMs.add((p - nanos) / 1e6);
            } else {
                specNanosByTick.put(tick, nanos);
            }
            prune(tick);
        }
        int self = -1;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == selfId) {
                self = i;
                break;
            }
        }
        if (self < 0) {
            return;
        }
        float sx = xs[self];
        float sy = ys[self];
        float r2 = viewRadius * viewRadius;
        Set<Integer> visible = new HashSet<>();
        for (int i = 0; i < ids.length; i++) {
            if (i == self) {
                continue;
            }
            float dx = xs[i] - sx;
            float dy = ys[i] - sy;
            if (dx * dx + dy * dy > r2) {
                continue;
            }
            if (los && rects != null && segmentBlocked(rects, sx, sy, xs[i], ys[i])) {
                continue;
            }
            visible.add(ids[i]);
        }
        if (measuring && prevVisible != null) {
            for (int id : visible) {
                if (prevVisible.contains(id)) {
                    continue;
                }
                Integer since = knownSince.get(id);
                if (since != null) {
                    if (since < tick) {
                        preknown++;
                    }
                    delayMs.add(0.0);
                    delayTicks.add(0.0);
                } else {
                    pending.put(id, new long[] {nanos, tick});
                }
            }
            for (var it = pending.entrySet().iterator(); it.hasNext();) {
                if (!visible.contains(it.next().getKey())) {
                    it.remove();
                    lostBeforeKnown++;
                }
            }
        }
        prevVisible = visible;
    }

    public synchronized void onPlayer(int tick, int[] ids, long nanos) {
        playerSnaps++;
        if (firstPlayerTick < 0) {
            firstPlayerTick = tick;
        }
        updateWarmup();
        boolean measuring = tick > warmupEndTick;
        if (measuring) {
            if (lastPlayerNanos >= 0) {
                intervalMs.add((nanos - lastPlayerNanos) / 1e6);
            }
            Long s = specNanosByTick.remove(tick);
            if (s != null) {
                skewMs.add((nanos - s) / 1e6);
            } else {
                playerNanosByTick.put(tick, nanos);
            }
            prune(tick);
        }
        lastPlayerNanos = nanos;
        Map<Integer, Integer> next = new HashMap<>();
        for (int id : ids) {
            if (id == selfId) {
                continue;
            }
            next.put(id, knownSince.getOrDefault(id, tick));
            long[] p = pending.get(id);
            if (p != null) {
                pending.remove(id);
                delayMs.add(Math.max(0, nanos - p[0]) / 1e6);
                delayTicks.add((double) Math.max(0, tick - (int) p[1]));
            }
        }
        knownSince = next;
    }

    /** Server-to-client latency from TIMESTAMP messages (same-machine only); ignored during warm-up. */
    public synchronized void addSnapshotLatencyMs(double ms) {
        if (warmupEndTick != Long.MAX_VALUE) {
            snapLatencyMs.add(ms);
        }
    }

    private void updateWarmup() {
        if (warmupEndTick == Long.MAX_VALUE && firstSpecTick >= 0 && firstPlayerTick >= 0) {
            warmupEndTick = (long) Math.max(firstSpecTick, firstPlayerTick) + warmupTicks;
        }
    }

    private void prune(int tick) {
        if (specNanosByTick.size() > 500) {
            specNanosByTick.keySet().removeIf(t -> t < tick - 200);
        }
        if (playerNanosByTick.size() > 500) {
            playerNanosByTick.keySet().removeIf(t -> t < tick - 200);
        }
    }

    /** True when the segment (x0,y0)-(x1,y1) crosses any rect (x,y,w,h per entry). Slab test, edges inclusive. */
    public static boolean segmentBlocked(float[] rects, float x0, float y0, float x1, float y1) {
        for (int i = 0; i + 3 < rects.length; i += 4) {
            float minX = rects[i];
            float minY = rects[i + 1];
            float maxX = minX + rects[i + 2];
            float maxY = minY + rects[i + 3];
            double t0 = 0;
            double t1 = 1;
            double dx = x1 - x0;
            double dy = y1 - y0;
            double[] p = {-dx, dx, -dy, dy};
            double[] q = {x0 - minX, maxX - x0, y0 - minY, maxY - y0};
            boolean hit = true;
            for (int k = 0; k < 4; k++) {
                if (p[k] == 0) {
                    if (q[k] < 0) {
                        hit = false;
                        break;
                    }
                } else {
                    double t = q[k] / p[k];
                    if (p[k] < 0) {
                        t0 = Math.max(t0, t);
                    } else {
                        t1 = Math.min(t1, t);
                    }
                    if (t0 > t1) {
                        hit = false;
                        break;
                    }
                }
            }
            if (hit) {
                return true;
            }
        }
        return false;
    }

    // ---- results ----
    public synchronized boolean warmedUp() {
        return lastSpecTick > warmupEndTick;
    }

    public synchronized List<Double> delayMs() {
        return new ArrayList<>(delayMs);
    }

    public synchronized List<Double> delayTicks() {
        return new ArrayList<>(delayTicks);
    }

    public synchronized List<Double> skewMs() {
        return new ArrayList<>(skewMs);
    }

    public synchronized List<Double> intervalMs() {
        return new ArrayList<>(intervalMs);
    }

    public synchronized List<Double> snapshotLatencyMs() {
        return new ArrayList<>(snapLatencyMs);
    }

    public synchronized int preknown() {
        return preknown;
    }

    /** Entities that became visible, were not known yet, and left the view before any snapshot delivered them. */
    public synchronized int lostBeforeKnown() {
        return lostBeforeKnown;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized int playerSnapshots() {
        return playerSnaps;
    }

    public synchronized int spectatorSnapshots() {
        return specSnaps;
    }

    /** CSV rows: label,metric,count,mean,median,p95,max. */
    public synchronized List<String> csvRows(String label) {
        List<String> rows = new ArrayList<>();
        rows.add(new Stats(delayMs).row(label, "new_entity_delay_ms"));
        rows.add(new Stats(delayTicks).row(label, "new_entity_delay_ticks"));
        rows.add(countRow(label, "preknown_count", preknown));
        rows.add(countRow(label, "lost_before_known_count", lostBeforeKnown));
        rows.add(new Stats(skewMs).row(label, "snapshot_skew_ms"));
        rows.add(new Stats(intervalMs).row(label, "snapshot_interval_ms"));
        rows.add(new Stats(snapLatencyMs).row(label, "snapshot_latency_ms"));
        return rows;
    }

    private static String countRow(String label, String metric, int n) {
        return String.format("%s,%s,%d,NaN,NaN,NaN,NaN", label, metric, n);
    }

    /** count/mean/median/p95/max over a sample list; all NaN when empty. */
    public static final class Stats {
        public final int count;
        public final double mean;
        public final double median;
        public final double p95;
        public final double max;

        public Stats(List<Double> v) {
            double[] a = v.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(a);
            count = a.length;
            if (count == 0) {
                mean = median = p95 = max = Double.NaN;
                return;
            }
            double sum = 0;
            for (double d : a) {
                sum += d;
            }
            mean = sum / count;
            median = count % 2 == 1 ? a[count / 2] : (a[count / 2 - 1] + a[count / 2]) / 2.0;
            p95 = a[Math.max(0, (int) Math.ceil(0.95 * count) - 1)];
            max = a[count - 1];
        }

        public String row(String label, String metric) {
            return String.format("%s,%s,%d,%.3f,%.3f,%.3f,%.3f", label, metric, count, mean, median, p95, max);
        }
    }
}
