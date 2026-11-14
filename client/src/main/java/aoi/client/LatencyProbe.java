package aoi.client;

import aoi.common.Protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Headless latency probe. Connects as a normal player (main port) and as a spectator (port + 1) and compares
 * "the client already knows where everyone is" (server --algo=all) with "the client only learns of an entity when
 * the server sends it" (--algo=distance / --algo=ray).
 *
 * Ground truth is GEOMETRIC, from the spectator's unfiltered world feed: an entity becomes visible at the first
 * spectator tick where it is within --view-radius of the probe player (with --los and obstacle rects known, also
 * not blocked by an obstacle). Time to know = receipt of the first player snapshot containing the entity minus
 * receipt of the spectator tick in which it became visible. Under --algo=all the player already holds every entity,
 * so delay is 0 and the entity is counted as preknown. See {@link LatencyTracker} for the matching rules.
 *
 * --net-delay-ms N simulates N ms of one-way network latency on the PLAYER connection only: each player snapshot
 * is held in a scheduled queue for N ms before the probe processes it (the reader thread never sleeps). The
 * spectator feed is the undelayed ground truth. Baseline stays at 0 (data is already on the client); AOI modes
 * gain about N ms, which is the cost of waiting for the server to tell you.
 *
 * Usage: LatencyProbe [--host localhost] [--port 7777] [--sport port+1] [--seconds 20]
 *                     [--out latency.csv] [--label run] [--still]
 *                     [--view-radius server radius] [--los] [--net-delay-ms 0] [--warmup-ticks 20]
 * --still disables the random-walk input (by default the player wanders so the visible set changes).
 * --los   also require a clear line of sight (use with --algo=ray); needs OBSTACLE_RECTS from the server, and
 *         without them the flag is ignored and a warning is printed.
 *
 * CSV (appended; header written once so runs can share one file), one row per metric per run:
 *   label,metric,count,mean,median,p95,max
 * metrics (ms unless noted):
 *   new_entity_delay_ms / new_entity_delay_ticks  time (server ticks) from geometric visibility to client knowing
 *   preknown_count        count column = entities the client already held when they became visible (no stats)
 *   lost_before_known_count  count column = entities that left view before any snapshot delivered them
 *   snapshot_skew_ms      signed (player receipt - spectator receipt) of the same tick, every matched tick
 *   snapshot_interval_ms  gap between consecutive player snapshots
 *   snapshot_latency_ms   client clock - server TIMESTAMP clock (same machine only; includes --net-delay-ms)
 */
public final class LatencyProbe {
    private static String opt(String[] args, String key, String def) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(key) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (args[i].startsWith(key + "=")) {
                return args[i].substring(key.length() + 1);
            }
        }
        return def;
    }

    private static boolean flag(String[] args, String key) {
        for (String a : args) {
            if (a.equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        String host = opt(args, "--host", "localhost");
        int port = Integer.parseInt(opt(args, "--port", String.valueOf(Protocol.DEFAULT_PORT)));
        int sport = Integer.parseInt(opt(args, "--sport", String.valueOf(port + 1)));
        double seconds = Double.parseDouble(opt(args, "--seconds", "20"));
        String out = opt(args, "--out", "latency.csv");
        String label = opt(args, "--label", "run");
        boolean still = flag(args, "--still");
        boolean los = flag(args, "--los");
        long netDelayMs = Long.parseLong(opt(args, "--net-delay-ms", "0"));
        int warmup = Integer.parseInt(opt(args, "--warmup-ticks", "20"));
        String viewRadiusArg = opt(args, "--view-radius", null);

        ScheduledExecutorService delayQueue = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "probe-net-delay");
            t.setDaemon(true);
            return t;
        });
        LatencyTracker tracker;
        try (Connection p = new Connection(host, port); SpectatorConnection s = new SpectatorConnection(host, sport)) {
            float viewRadius = viewRadiusArg == null ? p.radius : Float.parseFloat(viewRadiusArg);
            tracker = new LatencyTracker(p.clientId, viewRadius, warmup, los);
            System.out.println("player id=" + p.clientId + " server radius=" + p.radius + " view radius=" + viewRadius
                    + " world=" + p.worldW + "x" + p.worldH + " net-delay-ms=" + netDelayMs + " warmup-ticks=" + warmup
                    + " los=" + los);
            try {
                p.requestTimestamps();
            } catch (IOException e) {
                System.out.println("timestamp request failed: " + e.getMessage());
            }
            long delayNanos = netDelayMs * 1_000_000L;
            Thread tp = new Thread(() -> p.readLoop(snap -> {
                long arrived = System.nanoTime();
                long[] ts = p.lastTimestamp();
                double latencyMs = Double.NaN;
                if (ts != null && ts[0] == snap.tick()) {
                    latencyMs = (Protocol.nowEpochMicros() - ts[1]) / 1000.0 + netDelayMs;
                }
                final double lat = latencyMs;
                if (los) {
                    tracker.setRects(p.rects());
                }
                Runnable deliver = () -> {
                    tracker.onPlayer(snap.tick(), snap.ids(), arrived + delayNanos);
                    if (!Double.isNaN(lat)) {
                        tracker.addSnapshotLatencyMs(lat);
                    }
                };
                if (netDelayMs > 0) {
                    delayQueue.schedule(deliver, netDelayMs, TimeUnit.MILLISECONDS);
                } else {
                    deliver.run();
                }
            }), "probe-player");
            Thread ts = new Thread(() -> s.readLoop(f ->
                    tracker.onSpectator(f.tick(), f.ids(), f.xs(), f.ys(), System.nanoTime())), "probe-spectator");
            tp.setDaemon(true);
            ts.setDaemon(true);
            tp.start();
            ts.start();

            Random rnd = new Random(1);
            long end = System.nanoTime() + (long) (seconds * 1e9);
            float dx = 0;
            float dy = 0;
            int step = 0;
            while (System.nanoTime() < end) {
                if (!still) {
                    if (step++ % 10 == 0) {
                        dx = rnd.nextFloat() * 2 - 1;
                        dy = rnd.nextFloat() * 2 - 1;
                    }
                    try {
                        p.sendInput(dx, dy);
                    } catch (IOException e) {
                        break;
                    }
                }
                Thread.sleep(100);
            }
            if (los && p.rects() == null) {
                System.out.println("WARNING: --los set but the server sent no obstacle rects; line of sight was NOT applied");
            }
        }

        List<String> rows = tracker.csvRows(label);
        System.out.println("player snapshots=" + tracker.playerSnapshots() + " spectator snapshots="
                + tracker.spectatorSnapshots() + " warmed up=" + tracker.warmedUp()
                + " preknown=" + tracker.preknown() + " lost before known=" + tracker.lostBeforeKnown()
                + " unresolved pending=" + tracker.pendingCount());
        System.out.println("label,metric,count,mean,median,p95,max");
        rows.forEach(System.out::println);

        Path path = Path.of(out);
        boolean fresh = !Files.exists(path) || Files.size(path) == 0;
        StringBuilder sb = new StringBuilder();
        if (fresh) {
            sb.append("label,metric,count,mean,median,p95,max\n");
        }
        rows.forEach(r -> sb.append(r).append('\n'));
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        System.out.println("wrote " + path.toAbsolutePath());
        System.exit(0);
    }
}
