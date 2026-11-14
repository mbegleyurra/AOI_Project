package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.common.Protocol;
import aoi.server.algo.AllInterest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;

class TimestampMessageTest {

    @Test
    void wireLayoutAndRoundTrip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.writeTimestamp(new DataOutputStream(bytes), 77, 1_700_000_123_456_789L);
        byte[] b = bytes.toByteArray();
        assertEquals(1 + 4 + 8, b.length);
        assertEquals(Protocol.TIMESTAMP, b[0]);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        assertEquals(Protocol.TIMESTAMP, in.readByte());
        long[] t = Protocol.readTimestamp(in);
        assertEquals(77, t[0]);
        assertEquals(1_700_000_123_456_789L, t[1]);
    }

    @Test
    void nowEpochMicrosIsCloseToCurrentTime() {
        long micros = Protocol.nowEpochMicros();
        long ms = System.currentTimeMillis();
        assertTrue(Math.abs(micros / 1000 - ms) < 1000, "micros=" + micros + " ms=" + ms);
    }

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

    private static void skipSnapshotBody(DataInputStream in) throws IOException {
        in.readInt();
        int count = in.readInt();
        in.skipNBytes((long) count * Protocol.ENTITY_BYTES);
    }

    @Test
    void timestampPrecedesSnapshotOnlyAfterRequest() throws Exception {
        int port = freePort();
        new GameServer(new World(5, 3L), new AllInterest(), port).start();
        try (Socket sock = connect(port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            assertEquals(Protocol.WELCOME, in.readByte());
            in.readInt();
            in.readFloat();
            in.readFloat();
            in.readFloat();

            // Before the request: plain SNAPSHOT frames only.
            assertEquals(Protocol.SNAPSHOT, in.readByte());
            skipSnapshotBody(in);

            out.writeByte(Protocol.REQUEST_TIMESTAMPS);
            out.flush();

            long before = Protocol.nowEpochMicros();
            long[] ts = null;
            for (int i = 0; i < 200 && ts == null; i++) {
                byte type = in.readByte();
                if (type == Protocol.SNAPSHOT) {
                    skipSnapshotBody(in);
                } else {
                    assertEquals(Protocol.TIMESTAMP, type);
                    ts = Protocol.readTimestamp(in);
                }
            }
            assertTrue(ts != null, "no TIMESTAMP after REQUEST_TIMESTAMPS");
            assertEquals(Protocol.SNAPSHOT, in.readByte(), "SNAPSHOT must directly follow TIMESTAMP");
            int snapTick = in.readInt();
            assertEquals((int) ts[0], snapTick, "TIMESTAMP tick must match the following SNAPSHOT");
            long after = Protocol.nowEpochMicros();
            assertTrue(ts[1] >= before - 1_000_000 && ts[1] <= after, "server time out of range: " + ts[1]);
        }
    }
}
