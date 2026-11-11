package aoi.client;

import aoi.common.Protocol;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.function.Consumer;

/** Network side of the observer: connects to the spectator port and reads unfiltered full-world snapshots. */
public final class SpectatorConnection implements AutoCloseable {
    /**
     * @param isPlayer   parallel to ids/xs/ys
     * @param playerIds  ids of connected players
     * @param visible    visible[i] = entity ids the server sent to playerIds[i] this tick
     * @param occluded   occluded[i] = in-radius bots withheld from playerIds[i] because a wall blocks sight;
     *                   null when the connection did not ask for detail
     */
    public record FullSnapshot(int tick, int[] ids, float[] xs, float[] ys, boolean[] isPlayer,
                               int[] playerIds, int[][] visible, int[][] occluded) {
    }

    /** Wall grid from the server; cols == 0 means no obstacles. wall index = y * cols + x. */
    public record ObstacleMap(float cellSize, int cols, int rows, boolean[] wall) {
    }

    private final Socket socket;
    private final DataInputStream in;
    private volatile ObstacleMap map;
    private volatile float[] rects;

    public final float worldW;
    public final float worldH;
    public final float radius;

    /** Legacy feed: full snapshots only, no obstacle map, no occlusion lists. */
    public SpectatorConnection(String host, int port) throws IOException {
        this(host, port, false);
    }

    /** detail=true opts in to the obstacle map and per-player occlusion lists (spectator-only protocol extension). */
    public SpectatorConnection(String host, int port, boolean detail) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        byte type = in.readByte();
        if (type != Protocol.SPECTATE_WELCOME) {
            socket.close();
            throw new IOException("expected SPECTATE_WELCOME, got " + type
                    + " (is this the spectator port? default is player port + 1)");
        }
        worldW = in.readFloat();
        worldH = in.readFloat();
        radius = in.readFloat();
        if (detail) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeByte(Protocol.SPECTATE_REQUEST_DETAIL);
            out.flush();
        }
    }

    /** Latest obstacle map, or null until the server has sent one. */
    public ObstacleMap map() {
        return map;
    }

    /** Obstacle rectangles (x,y,w,h per obstacle, world units) from OBSTACLE_RECTS, or null if none was sent. */
    public float[] rects() {
        return rects;
    }

    /** Blocks reading snapshots until the connection closes. */
    public void readLoop(Consumer<FullSnapshot> onSnapshot) {
        try {
            while (true) {
                byte type = in.readByte();
                if (type == Protocol.OBSTACLE_RECTS) {
                    rects = Protocol.readObstacleRects(in);
                    continue;
                }
                if (type == Protocol.OBSTACLE_MAP) {
                    map = readMap();
                    continue;
                }
                boolean detail = type == Protocol.DETAIL_SNAPSHOT;
                if (type != Protocol.FULL_SNAPSHOT && !detail) {
                    throw new IOException("unexpected message type " + type);
                }
                int tick = in.readInt();
                int n = in.readInt();
                int[] ids = new int[n];
                float[] xs = new float[n];
                float[] ys = new float[n];
                boolean[] isPlayer = new boolean[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = in.readInt();
                    xs[i] = in.readFloat();
                    ys[i] = in.readFloat();
                    isPlayer[i] = in.readByte() != 0;
                }
                int p = in.readInt();
                int[] playerIds = new int[p];
                int[][] visible = new int[p][];
                int[][] occluded = detail ? new int[p][] : null;
                for (int i = 0; i < p; i++) {
                    playerIds[i] = in.readInt();
                    int m = in.readInt();
                    visible[i] = new int[m];
                    for (int j = 0; j < m; j++) {
                        visible[i][j] = in.readInt();
                    }
                    if (detail) {
                        int h = in.readInt();
                        occluded[i] = new int[h];
                        for (int j = 0; j < h; j++) {
                            occluded[i][j] = in.readInt();
                        }
                    }
                }
                onSnapshot.accept(new FullSnapshot(tick, ids, xs, ys, isPlayer, playerIds, visible, occluded));
            }
        } catch (IOException e) {
            // closed
        }
    }

    private ObstacleMap readMap() throws IOException {
        float cell = in.readFloat();
        int cols = in.readInt();
        int rows = in.readInt();
        boolean[] wall = new boolean[cols * rows];
        byte[] bits = new byte[(cols * rows + 7) / 8];
        in.readFully(bits);
        for (int i = 0; i < wall.length; i++) {
            wall[i] = (bits[i >> 3] >> (i & 7) & 1) != 0;
        }
        return new ObstacleMap(cell, cols, rows, wall);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
