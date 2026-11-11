package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.common.Protocol;
import aoi.server.algo.RayVisibilityInterest;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spectator DETAIL extension: obstacle map + per-player occluded lists, opt-in, player protocol untouched. */
class SpectatorDetailIntegrationTest {
    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Socket connect(int port) throws Exception {
        IOException last = null;
        for (int i = 0; i < 50; i++) {
            try {
                Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
                s.setSoTimeout(5000);
                return s;
            } catch (IOException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last;
    }

    @Test
    void detailSpectatorGetsMapAndOccludedSetsConsistentWithServerLineOfSight() throws Exception {
        int port = freePort();
        int sport = freePort();
        double radius = 450; // large, so buildings are certain to hide some bots
        Obstacles obstacles = Obstacles.generate(7L);
        World world = new World(300, 7L);
        world.setObstacles(obstacles);
        new GameServer(world, new RayVisibilityInterest(obstacles, radius), port, sport).start();

        try (Socket spec = connect(sport); Socket player = connect(port)) {
            DataInputStream in = new DataInputStream(spec.getInputStream());
            assertEquals(Protocol.SPECTATE_WELCOME, in.readByte());
            in.readFloat();
            in.readFloat();
            in.readFloat();
            DataOutputStream out = new DataOutputStream(spec.getOutputStream());
            out.writeByte(Protocol.SPECTATE_REQUEST_DETAIL);
            out.flush();

            DataInputStream pin = new DataInputStream(player.getInputStream());
            assertEquals(Protocol.WELCOME, pin.readByte());
            int pid = pin.readInt();

            boolean mapSeen = false;
            boolean rectsSeen = false;
            boolean sawOccluded = false;
            boolean sawDetailForPlayer = false;
            for (int frame = 0; frame < 200 && !(sawOccluded); frame++) {
                byte type = in.readByte();
                if (type == Protocol.OBSTACLE_RECTS) {
                    // Sent once to every spectator right after the welcome when the world has obstacles.
                    assertTrue(Protocol.readObstacleRects(in).length > 0, "obstacle server must send rectangles");
                    rectsSeen = true;
                    continue;
                }
                if (type == Protocol.OBSTACLE_MAP) {
                    float cell = in.readFloat();
                    int cols = in.readInt();
                    int rows = in.readInt();
                    assertEquals((float) obstacles.cellSize(), cell);
                    assertEquals(obstacles.cols(), cols);
                    assertEquals(obstacles.rows(), rows);
                    byte[] bits = new byte[(cols * rows + 7) / 8];
                    in.readFully(bits);
                    for (int y = 0; y < rows; y++) {
                        for (int x = 0; x < cols; x++) {
                            int idx = y * cols + x;
                            boolean wall = (bits[idx >> 3] >> (idx & 7) & 1) != 0;
                            assertEquals(obstacles.blockedCell(x, y), wall, "wall bit at " + x + "," + y);
                        }
                    }
                    mapSeen = true;
                    continue;
                }
                if (type == Protocol.FULL_SNAPSHOT) {
                    skipFull(in); // a frame sent before the server registered the request
                    continue;
                }
                assertEquals(Protocol.DETAIL_SNAPSHOT, type);
                in.readInt();
                int count = in.readInt();
                Map<Integer, double[]> pos = new HashMap<>();
                Set<Integer> bots = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    int id = in.readInt();
                    pos.put(id, new double[]{in.readFloat(), in.readFloat()});
                    if (in.readByte() == 0) {
                        bots.add(id);
                    }
                }
                int players = in.readInt();
                for (int i = 0; i < players; i++) {
                    int id = in.readInt();
                    Set<Integer> sent = new HashSet<>();
                    for (int j = in.readInt(); j > 0; j--) {
                        sent.add(in.readInt());
                    }
                    Set<Integer> hidden = new HashSet<>();
                    for (int j = in.readInt(); j > 0; j--) {
                        hidden.add(in.readInt());
                    }
                    if (id != pid) {
                        continue;
                    }
                    sawDetailForPlayer = true;
                    double[] me = pos.get(pid);
                    assertTrue(Collections_disjoint(sent, hidden), "sent and occluded must not overlap");
                    // Independently recompute from the same frame: every in-radius bot is either sent or occluded,
                    // and the split is exactly line-of-sight.
                    for (int b : bots) {
                        double[] p = pos.get(b);
                        double d = Math.hypot(p[0] - me[0], p[1] - me[1]);
                        if (d > radius - 1.0) {
                            continue; // float rounding at the rim
                        }
                        boolean los = obstacles.lineOfSight(me[0], me[1], p[0], p[1]);
                        if (los) {
                            assertTrue(sent.contains(b), "visible in-radius bot " + b + " must be sent");
                            assertFalse(hidden.contains(b));
                        } else {
                            assertTrue(hidden.contains(b), "blocked in-radius bot " + b + " must be occluded");
                            assertFalse(sent.contains(b));
                        }
                    }
                    for (int h : hidden) {
                        assertTrue(bots.contains(h), "occluded entries are bots");
                    }
                    sawOccluded |= !hidden.isEmpty();
                }
            }
            assertTrue(mapSeen, "obstacle map must arrive after the request");
            assertTrue(rectsSeen, "OBSTACLE_RECTS must arrive before the first snapshot");
            assertTrue(sawDetailForPlayer, "player must appear in a DETAIL_SNAPSHOT");
            assertTrue(sawOccluded, "expected at least one occluded bot with radius 450 and 60 buildings");
        }
    }

    @Test
    void worldWithoutObstaclesSendsEmptyMapAndNoOccluded() throws Exception {
        int port = freePort();
        int sport = freePort();
        new GameServer(new World(100, 3L), new DistanceInterest(120), port, sport).start();
        try (Socket spec = connect(sport); Socket player = connect(port)) {
            DataInputStream in = new DataInputStream(spec.getInputStream());
            assertEquals(Protocol.SPECTATE_WELCOME, in.readByte());
            in.readFloat();
            in.readFloat();
            in.readFloat();
            DataOutputStream out = new DataOutputStream(spec.getOutputStream());
            out.writeByte(Protocol.SPECTATE_REQUEST_DETAIL);
            out.flush();
            boolean mapSeen = false;
            for (int frame = 0; frame < 100; frame++) {
                byte type = in.readByte();
                if (type == Protocol.OBSTACLE_MAP) {
                    in.readFloat();
                    assertEquals(0, in.readInt());
                    assertEquals(0, in.readInt());
                    mapSeen = true;
                    continue;
                }
                if (type == Protocol.FULL_SNAPSHOT) {
                    skipFull(in);
                    continue;
                }
                assertEquals(Protocol.DETAIL_SNAPSHOT, type);
                in.readInt();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    in.readInt();
                    in.readFloat();
                    in.readFloat();
                    in.readByte();
                }
                int players = in.readInt();
                for (int i = 0; i < players; i++) {
                    in.readInt();
                    for (int j = in.readInt(); j > 0; j--) {
                        in.readInt();
                    }
                    assertEquals(0, in.readInt(), "no obstacles means nothing can be occluded");
                }
                if (mapSeen && players > 0) {
                    return;
                }
            }
            throw new AssertionError("never saw a detail frame with a player after the empty map");
        }
    }

    private static boolean Collections_disjoint(Set<Integer> a, Set<Integer> b) {
        return java.util.Collections.disjoint(a, b);
    }

    private static void skipFull(DataInputStream in) throws IOException {
        in.readInt();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            in.readInt();
            in.readFloat();
            in.readFloat();
            in.readByte();
        }
        int p = in.readInt();
        for (int i = 0; i < p; i++) {
            in.readInt();
            for (int j = in.readInt(); j > 0; j--) {
                in.readInt();
            }
        }
    }
}
