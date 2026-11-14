package aoi.client;

import aoi.common.Protocol;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.function.Consumer;

/** Network side of the client, free of any UI so it can also be used by the headless probe. */
public final class Connection implements AutoCloseable {
    public record Snapshot(int tick, int[] ids, float[] xs, float[] ys) {
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    private volatile float[] rects;
    /** {tick, serverEpochMicros} of the latest TIMESTAMP message, or null if none has arrived. */
    private volatile long[] lastTimestamp;

    public final int clientId;
    public final float worldW;
    public final float worldH;
    public final float radius;

    public Connection(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        byte type = in.readByte();
        if (type != Protocol.WELCOME) {
            throw new IOException("expected WELCOME, got " + type);
        }
        clientId = in.readInt();
        worldW = in.readFloat();
        worldH = in.readFloat();
        radius = in.readFloat();
    }

    /** Obstacle rectangles (x,y,w,h per obstacle, world units) from OBSTACLE_RECTS, or null if none was sent. */
    public float[] rects() {
        return rects;
    }

    /** Latest {tick, serverEpochMicros} from a TIMESTAMP message, or null if timestamps were not requested/sent. */
    public long[] lastTimestamp() {
        return lastTimestamp;
    }

    /** Opts in to server timestamps (one TIMESTAMP message before each SNAPSHOT). Old servers ignore it. */
    public synchronized void requestTimestamps() throws IOException {
        out.writeByte(Protocol.REQUEST_TIMESTAMPS);
        out.flush();
    }

    /** Blocks reading snapshots until the connection closes. */
    public void readLoop(Consumer<Snapshot> onSnapshot) {
        try {
            while (true) {
                byte type = in.readByte();
                if (type == Protocol.OBSTACLE_RECTS) {
                    rects = Protocol.readObstacleRects(in);
                    continue;
                }
                if (type == Protocol.TIMESTAMP) {
                    lastTimestamp = Protocol.readTimestamp(in);
                    continue;
                }
                if (type != Protocol.SNAPSHOT) {
                    throw new IOException("unexpected message type " + type);
                }
                int tick = in.readInt();
                int n = in.readInt();
                int[] ids = new int[n];
                float[] xs = new float[n];
                float[] ys = new float[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = in.readInt();
                    xs[i] = in.readFloat();
                    ys[i] = in.readFloat();
                }
                onSnapshot.accept(new Snapshot(tick, ids, xs, ys));
            }
        } catch (IOException e) {
            // closed
        }
    }

    public synchronized void sendInput(float dx, float dy) throws IOException {
        out.writeByte(Protocol.INPUT);
        out.writeFloat(dx);
        out.writeFloat(dy);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
