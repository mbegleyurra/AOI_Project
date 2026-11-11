package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.common.Protocol;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spectator port: full unfiltered world, no avatar spawned, per-player visible ids match the AOI. */
class SpectatorIntegrationTest {
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
    void spectatorSeesEverythingWithoutSpawningAvatar() throws Exception {
        int port = freePort();
        int sport = freePort();
        int bots = 200;
        double radius = 100;
        new GameServer(new World(bots, 42L), new DistanceInterest(radius), port, sport).start();

        try (Socket spec = connect(sport)) {
            DataInputStream in = new DataInputStream(spec.getInputStream());
            assertEquals(Protocol.SPECTATE_WELCOME, in.readByte());
            in.readFloat();
            in.readFloat();
            assertEquals((float) radius, in.readFloat());

            // No player connected: only bots, no players listed.
            assertEquals(Protocol.FULL_SNAPSHOT, in.readByte());
            in.readInt();
            int n = in.readInt();
            assertEquals(bots, n, "spectator must not spawn an avatar");
            for (int i = 0; i < n; i++) {
                in.readInt();
                in.readFloat();
                in.readFloat();
                assertEquals(0, in.readByte());
            }
            assertEquals(0, in.readInt(), "spectator is not a player");

            // Now a player joins; wait for a snapshot that lists it.
            try (Socket player = connect(port)) {
                DataInputStream pin = new DataInputStream(player.getInputStream());
                assertEquals(Protocol.WELCOME, pin.readByte());
                int pid = pin.readInt();
                pin.readFloat();
                pin.readFloat();
                pin.readFloat();

                for (int attempt = 0; attempt < 60; attempt++) {
                    assertEquals(Protocol.FULL_SNAPSHOT, in.readByte());
                    in.readInt();
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) {
                        in.readInt();
                        in.readFloat();
                        in.readFloat();
                        in.readByte();
                    }
                    int players = in.readInt();
                    Set<Integer> vis = new HashSet<>();
                    boolean found = false;
                    for (int i = 0; i < players; i++) {
                        int id = in.readInt();
                        int m = in.readInt();
                        for (int j = 0; j < m; j++) {
                            int v = in.readInt();
                            if (id == pid) {
                                vis.add(v);
                            }
                        }
                        found |= id == pid;
                    }
                    if (found) {
                        assertEquals(bots + 1, count, "spectator gets bots plus the one player");
                        assertTrue(vis.contains(pid));
                        assertTrue(vis.size() < count, "player view must be a strict subset of the full world");
                        assertFalse(vis.isEmpty());
                        return;
                    }
                }
                throw new AssertionError("spectator never reported the player");
            }
        }
    }
}
