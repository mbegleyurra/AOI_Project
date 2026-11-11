package aoi.client;

import aoi.common.Protocol;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless check of what the observer window consumes: one normal player plus one detail spectator.
 * Passes when the obstacle map arrived, detail frames carry occlusion lists, the sent set equals what the
 * player really received for the same tick, sent/occluded never overlap, occluded bots are inside the radius,
 * and (server run with --algo=ray) every in-radius bot is either sent or occluded; occlusion was seen at least once.
 * Usage: SpectatorDetailProbe [host] [playerPort] [spectatorPort]
 */
public final class SpectatorDetailProbe {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
        int sport = args.length > 2 ? Integer.parseInt(args[2]) : Protocol.DEFAULT_SPECTATOR_PORT;

        Map<Integer, int[]> playerSnaps = new ConcurrentHashMap<>();
        Map<Integer, SpectatorConnection.FullSnapshot> specSnaps = new ConcurrentHashMap<>();

        try (Connection p = new Connection(host, port);
             SpectatorConnection s = new SpectatorConnection(host, sport, true)) {
            Thread tp = new Thread(() -> p.readLoop(sn -> playerSnaps.put(sn.tick(), sn.ids())));
            Thread ts = new Thread(() -> s.readLoop(sn -> specSnaps.put(sn.tick(), sn)));
            tp.setDaemon(true);
            ts.setDaemon(true);
            tp.start();
            ts.start();
            Thread.sleep(3000);

            SpectatorConnection.ObstacleMap map = s.map();
            int walls = 0;
            if (map != null) {
                for (boolean w : map.wall()) {
                    walls += w ? 1 : 0;
                }
            }
            double r2 = (double) s.radius * s.radius;
            int compared = 0;
            int sentMismatch = 0;
            int overlap = 0;
            int outsideRadius = 0;
            int unaccounted = 0;
            int framesWithOccluded = 0;
            int noDetail = 0;
            int maxOccluded = 0;
            for (var e : specSnaps.entrySet()) {
                SpectatorConnection.FullSnapshot f = e.getValue();
                if (f.occluded() == null) {
                    noDetail++;
                    continue;
                }
                int[] got = playerSnaps.get(e.getKey());
                int idx = -1;
                for (int i = 0; i < f.playerIds().length; i++) {
                    if (f.playerIds()[i] == p.clientId) {
                        idx = i;
                    }
                }
                if (got == null || idx < 0) {
                    continue;
                }
                compared++;
                int[] a = got.clone();
                int[] b = f.visible()[idx].clone();
                Arrays.sort(a);
                Arrays.sort(b);
                if (!Arrays.equals(a, b)) {
                    sentMismatch++;
                }
                Set<Integer> sent = new HashSet<>();
                for (int id : b) {
                    sent.add(id);
                }
                Set<Integer> hidden = new HashSet<>();
                for (int id : f.occluded()[idx]) {
                    hidden.add(id);
                    if (sent.contains(id)) {
                        overlap++;
                    }
                }
                if (!hidden.isEmpty()) {
                    framesWithOccluded++;
                    maxOccluded = Math.max(maxOccluded, hidden.size());
                }
                float px = 0;
                float py = 0;
                for (int i = 0; i < f.ids().length; i++) {
                    if (f.ids()[i] == p.clientId) {
                        px = f.xs()[i];
                        py = f.ys()[i];
                    }
                }
                for (int i = 0; i < f.ids().length; i++) {
                    if (f.isPlayer()[i]) {
                        continue;
                    }
                    double dx = f.xs()[i] - px;
                    double dy = f.ys()[i] - py;
                    boolean in = dx * dx + dy * dy <= r2;
                    int id = f.ids()[i];
                    if (hidden.contains(id) && !in) {
                        outsideRadius++;
                    }
                    // rim tolerance: only judge bots clearly inside the radius
                    if (in && Math.sqrt(dx * dx + dy * dy) < s.radius - 1.0 && !sent.contains(id) && !hidden.contains(id)) {
                        unaccounted++;
                    }
                }
            }
            System.out.println("map=" + (map == null ? "none" : map.cols() + "x" + map.rows() + " cell=" + map.cellSize()
                    + " wallCells=" + walls));
            System.out.println("detail snapshots=" + specSnaps.size() + " without detail=" + noDetail
                    + " compared ticks=" + compared + " sentMismatch=" + sentMismatch + " overlap=" + overlap
                    + " occludedOutsideRadius=" + outsideRadius + " inRadiusUnaccounted=" + unaccounted
                    + " framesWithOccluded=" + framesWithOccluded + " maxOccluded=" + maxOccluded);
            boolean ok = map != null && map.cols() > 0 && walls > 0 && noDetail == 0 && compared >= 10
                    && sentMismatch == 0 && overlap == 0 && outsideRadius == 0 && unaccounted == 0
                    && framesWithOccluded > 0;
            System.out.println(ok ? "SPECTATOR DETAIL PROBE OK" : "SPECTATOR DETAIL PROBE FAILED");
            System.exit(ok ? 0 : 1);
        }
    }
}
