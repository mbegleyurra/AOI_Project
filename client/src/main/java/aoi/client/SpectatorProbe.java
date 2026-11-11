package aoi.client;

import aoi.common.Protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless check: one normal player plus one spectator. Passes when the spectator receives every entity
 * and, for the same tick, the visible set the spectator reports for the player equals what the player got.
 * Usage: SpectatorProbe [host] [playerPort] [spectatorPort]
 */
public final class SpectatorProbe {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
        int sport = args.length > 2 ? Integer.parseInt(args[2]) : Protocol.DEFAULT_SPECTATOR_PORT;

        Map<Integer, int[]> playerSnaps = new ConcurrentHashMap<>();
        Map<Integer, SpectatorConnection.FullSnapshot> specSnaps = new ConcurrentHashMap<>();

        try (Connection p = new Connection(host, port); SpectatorConnection s = new SpectatorConnection(host, sport)) {
            System.out.println("player id=" + p.clientId + " radius=" + p.radius + "; spectator radius=" + s.radius);
            Thread tp = new Thread(() -> p.readLoop(sn -> playerSnaps.put(sn.tick(), sn.ids())));
            Thread ts = new Thread(() -> s.readLoop(sn -> specSnaps.put(sn.tick(), sn)));
            tp.setDaemon(true);
            ts.setDaemon(true);
            tp.start();
            ts.start();
            Thread.sleep(2500);

            int compared = 0;
            int mismatches = 0;
            int maxTotal = 0;
            int maxPlayerVisible = 0;
            for (var e : specSnaps.entrySet()) {
                int[] got = playerSnaps.get(e.getKey());
                SpectatorConnection.FullSnapshot f = e.getValue();
                maxTotal = Math.max(maxTotal, f.ids().length);
                if (got == null) {
                    continue;
                }
                int idx = -1;
                for (int i = 0; i < f.playerIds().length; i++) {
                    if (f.playerIds()[i] == p.clientId) {
                        idx = i;
                    }
                }
                if (idx < 0) {
                    mismatches++;
                    continue;
                }
                int[] a = got.clone();
                int[] b = f.visible()[idx].clone();
                java.util.Arrays.sort(a);
                java.util.Arrays.sort(b);
                compared++;
                maxPlayerVisible = Math.max(maxPlayerVisible, a.length);
                if (!java.util.Arrays.equals(a, b)) {
                    mismatches++;
                }
            }
            System.out.println("spectator snapshots=" + specSnaps.size() + " player snapshots=" + playerSnaps.size()
                    + " compared ticks=" + compared + " mismatches=" + mismatches);
            System.out.println("spectator max entities per snapshot=" + maxTotal
                    + "; player max visible per snapshot=" + maxPlayerVisible);
            boolean ok = compared >= 10 && mismatches == 0 && maxTotal > maxPlayerVisible;
            System.out.println(ok ? "SPECTATOR PROBE OK" : "SPECTATOR PROBE FAILED");
            System.exit(ok ? 0 : 1);
        }
    }
}
