package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.common.Protocol;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Starts a real GameServer on a free port and reads the wire protocol with raw sockets. */
class GameServerIntegrationTest {

    private static final class Snap {
        int tick;
        final Map<Integer, float[]> entities = new HashMap<>();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Socket connect(int port) throws IOException, InterruptedException {
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

    private static Snap readSnapshot(DataInputStream in) throws IOException {
        assertEquals(Protocol.SNAPSHOT, in.readByte(), "expected SNAPSHOT message type");
        Snap s = new Snap();
        s.tick = in.readInt();
        int count = in.readInt();
        assertTrue(count >= 0 && count < 100_000, "implausible entity count " + count);
        for (int i = 0; i < count; i++) {
            int id = in.readInt();
            float x = in.readFloat();
            float y = in.readFloat();
            assertTrue(s.entities.put(id, new float[]{x, y}) == null, "duplicate id in snapshot: " + id);
        }
        return s;
    }

    @Test
    void welcomeAndSnapshotContents() throws Exception {
        double radius = 150.0;
        int port = freePort();
        new GameServer(new World(200, 42L), new DistanceInterest(radius), port).start();

        try (Socket sock = connect(port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());

            assertEquals(Protocol.WELCOME, in.readByte(), "first message must be WELCOME");
            int id = in.readInt();
            float w = in.readFloat();
            float h = in.readFloat();
            float r = in.readFloat();
            assertTrue(id > 200, "player id should come after the 200 bots, got " + id);
            assertEquals((float) World.SIZE, w);
            assertEquals((float) World.SIZE, h);
            assertEquals((float) radius, r);

            Snap first = readSnapshot(in);
            assertTrue(first.tick > 0);
            float[] me = first.entities.get(id);
            assertTrue(me != null, "own avatar missing from snapshot");
            assertTrue(me[0] >= 0 && me[0] <= World.SIZE && me[1] >= 0 && me[1] <= World.SIZE, "avatar outside world");

            for (Map.Entry<Integer, float[]> e : first.entities.entrySet()) {
                double d = Math.hypot(e.getValue()[0] - me[0], e.getValue()[1] - me[1]);
                assertTrue(d <= radius + 0.01, "entity " + e.getKey() + " at distance " + d + " exceeds radius");
            }

            Snap second = readSnapshot(in);
            assertTrue(second.tick > first.tick, "tick must advance: " + first.tick + " -> " + second.tick);
            assertTrue(second.entities.containsKey(id));
        }
    }

    @Test
    void emptyWorldSnapshotContainsOnlyThePlayer() throws Exception {
        int port = freePort();
        new GameServer(new World(0, 1L), new DistanceInterest(100), port).start();

        try (Socket sock = connect(port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            assertEquals(Protocol.WELCOME, in.readByte());
            int id = in.readInt();
            in.readFloat();
            in.readFloat();
            in.readFloat();
            Snap s = readSnapshot(in);
            assertEquals(1, s.entities.size());
            assertTrue(s.entities.containsKey(id));
        }
    }

    @Test
    void inputMovesTheAvatar() throws Exception {
        int port = freePort();
        new GameServer(new World(0, 5L), new DistanceInterest(100), port).start();

        try (Socket sock = connect(port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            assertEquals(Protocol.WELCOME, in.readByte());
            int id = in.readInt();
            in.readFloat();
            in.readFloat();
            in.readFloat();

            float[] start = readSnapshot(in).entities.get(id);
            out.writeByte(Protocol.INPUT);
            out.writeFloat(0f);
            out.writeFloat(1f);
            out.flush();

            float[] moved = start;
            for (int i = 0; i < 60 && moved[1] == start[1]; i++) {
                moved = readSnapshot(in).entities.get(id);
            }
            assertNotEquals(start[1], moved[1], "avatar y did not change after INPUT");
            assertEquals(start[0], moved[0], 0.01f, "x must not change for a pure-y input");
        }
    }

    @Test
    void secondClientSeesFirstWhenInRange() throws Exception {
        int port = freePort();
        // Radius larger than the world diagonal: every client must see every other one.
        new GameServer(new World(0, 9L), new DistanceInterest(2000), port).start();

        try (Socket a = connect(port); Socket b = connect(port)) {
            DataInputStream ina = new DataInputStream(a.getInputStream());
            DataInputStream inb = new DataInputStream(b.getInputStream());
            assertEquals(Protocol.WELCOME, ina.readByte());
            int idA = ina.readInt();
            ina.readFloat();
            ina.readFloat();
            ina.readFloat();
            assertEquals(Protocol.WELCOME, inb.readByte());
            int idB = inb.readInt();
            inb.readFloat();
            inb.readFloat();
            inb.readFloat();
            assertNotEquals(idA, idB);

            Snap sa = null;
            for (int i = 0; i < 40; i++) {
                sa = readSnapshot(ina);
                if (sa.entities.size() == 2) {
                    break;
                }
            }
            assertTrue(sa.entities.containsKey(idA) && sa.entities.containsKey(idB), "A should eventually see both A and B");
        }
    }
}
