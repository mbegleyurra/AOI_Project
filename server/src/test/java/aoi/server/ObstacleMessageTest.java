package aoi.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.common.Protocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;

/** OBSTACLE_RECTS wire message: codec round trip, and delivery on both the player and the spectator connection. */
class ObstacleMessageTest {
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
    void codecRoundTripAndLayout() throws Exception {
        float[] rects = {10, 20, 30, 40, 100.5f, 200.25f, 50, 60};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.writeObstacleRects(new DataOutputStream(bytes), rects);
        byte[] wire = bytes.toByteArray();
        assertEquals(1 + 4 + 8 * 4, wire.length, "type byte + int count + 4 floats per rect");
        assertEquals(Protocol.OBSTACLE_RECTS, wire[0]);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire));
        assertEquals(Protocol.OBSTACLE_RECTS, in.readByte());
        assertArrayEquals(rects, Protocol.readObstacleRects(in));
        assertEquals(0, in.available());
    }

    @Test
    void typeByteDoesNotCollideWithAnyOtherMessage() {
        byte[] others = {Protocol.WELCOME, Protocol.SNAPSHOT, Protocol.INPUT, Protocol.SPECTATE_WELCOME,
            Protocol.FULL_SNAPSHOT, Protocol.SPECTATE_REQUEST_DETAIL, Protocol.OBSTACLE_MAP, Protocol.DETAIL_SNAPSHOT};
        for (byte b : others) {
            assertTrue(b != Protocol.OBSTACLE_RECTS, "OBSTACLE_RECTS reuses type byte " + b);
        }
    }

    @Test
    void rejectsMalformedMessages() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.writeObstacleRects(new DataOutputStream(new ByteArrayOutputStream()), new float[3]));
        byte[] negative = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThrows(IOException.class,
                () -> Protocol.readObstacleRects(new DataInputStream(new ByteArrayInputStream(negative))));
    }

    @Test
    void playerGetsObstaclesRightAfterWelcomeBeforeAnySnapshot() throws Exception {
        Obstacles o = Obstacles.generateRects(7L);
        World world = new World(50, 7L);
        world.setObstacles(o);
        int port = freePort();
        new GameServer(world, new DistanceInterest(150), port).start();

        try (Socket sock = connect(port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            assertEquals(Protocol.WELCOME, in.readByte());
            in.readInt();
            in.readFloat();
            in.readFloat();
            in.readFloat();
            assertEquals(Protocol.OBSTACLE_RECTS, in.readByte(), "obstacles must directly follow WELCOME");
            assertArrayEquals(o.rectsAsFloats(), Protocol.readObstacleRects(in));
            assertEquals(Protocol.SNAPSHOT, in.readByte(), "snapshots follow the obstacle message");
        }
    }

    @Test
    void spectatorGetsObstaclesRightAfterSpectateWelcome() throws Exception {
        Obstacles o = Obstacles.generateRects(8L);
        World world = new World(50, 8L);
        world.setObstacles(o);
        int port = freePort();
        int sport = freePort();
        new GameServer(world, new DistanceInterest(150), port, sport).start();

        try (Socket sock = connect(sport)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            assertEquals(Protocol.SPECTATE_WELCOME, in.readByte());
            in.readFloat();
            in.readFloat();
            in.readFloat();
            assertEquals(Protocol.OBSTACLE_RECTS, in.readByte());
            assertArrayEquals(o.rectsAsFloats(), Protocol.readObstacleRects(in));
            assertEquals(Protocol.FULL_SNAPSHOT, in.readByte());
        }
    }

    @Test
    void serverWithoutObstaclesSendsNothingExtra() throws Exception {
        int port = freePort();
        new GameServer(new World(10, 1L), new DistanceInterest(150), port).start();
        try (Socket sock = connect(port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            assertEquals(Protocol.WELCOME, in.readByte());
            in.readInt();
            in.readFloat();
            in.readFloat();
            in.readFloat();
            assertEquals(Protocol.SNAPSHOT, in.readByte(), "legacy sequence must be unchanged without obstacles");
        }
    }
}
