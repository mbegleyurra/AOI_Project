package aoi.server;

import aoi.common.Protocol;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Authoritative tick-based server: steps the world, filters it per client through an InterestManager, sends snapshots. */
public final class GameServer {
    public static final int TICK_HZ = 20;

    private final World world;
    private final InterestManager interest;
    private final int port;
    private final int spectatorPort;
    private final List<ClientConn> clients = new CopyOnWriteArrayList<>();
    private final List<SpectatorConn> spectators = new CopyOnWriteArrayList<>();

    private final AtomicLong entitiesSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private int tick;

    public GameServer(World world, InterestManager interest, int port) {
        this(world, interest, port, 0);
    }

    /** spectatorPort <= 0 disables the spectator listener. */
    public GameServer(World world, InterestManager interest, int port, int spectatorPort) {
        this.world = world;
        this.interest = interest;
        this.port = port;
        this.spectatorPort = spectatorPort;
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket(port);
        System.out.println("Server listening on " + port + ", algorithm " + interest.name());

        Thread acceptor = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    s.setTcpNoDelay(true);
                    ClientConn c = new ClientConn(s);
                    clients.add(c);
                    c.startReader();
                } catch (IOException e) {
                    return;
                }
            }
        }, "acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        if (spectatorPort > 0) {
            ServerSocket sss = new ServerSocket(spectatorPort);
            System.out.println("Spectator port " + spectatorPort + " (full unfiltered world)");
            Thread specAcceptor = new Thread(() -> {
                while (!sss.isClosed()) {
                    try {
                        Socket s = sss.accept();
                        s.setTcpNoDelay(true);
                        SpectatorConn c = new SpectatorConn(s);
                        spectators.add(c);
                        c.startReader();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "spectator-acceptor");
            specAcceptor.setDaemon(true);
            specAcceptor.start();
        }

        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();
        loop.scheduleAtFixedRate(this::tickOnce, 0, 1000 / TICK_HZ, TimeUnit.MILLISECONDS);
        loop.scheduleAtFixedRate(this::report, 1, 1, TimeUnit.SECONDS);
    }

    private void tickOnce() {
        try {
            world.step(1.0 / TICK_HZ);
            tick++;
            for (ClientConn c : clients) {
                c.sendSnapshot(tick);
            }
            if (!spectators.isEmpty()) {
                byte[] plain = null;
                byte[] detail = null;
                for (SpectatorConn s : spectators) {
                    if (s.detail) {
                        if (detail == null) {
                            detail = buildSnapshot(tick, true);
                        }
                        s.send(detail);
                    } else {
                        if (plain == null) {
                            plain = buildSnapshot(tick, false);
                        }
                        s.send(plain);
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Full world plus, per player, the ids that player was just sent. Spectators are not players.
     * detail=true builds the opt-in DETAIL_SNAPSHOT, which also lists per player the in-radius bots
     * that were withheld because an obstacle blocks the line of sight.
     */
    private byte[] buildSnapshot(int tick, boolean detail) throws IOException {
        List<Entity> all = new ArrayList<>(world.all());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(all.size() * 13 + 64);
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeByte(detail ? Protocol.DETAIL_SNAPSHOT : Protocol.FULL_SNAPSHOT);
        o.writeInt(tick);
        o.writeInt(all.size());
        for (Entity e : all) {
            o.writeInt(e.id);
            o.writeFloat((float) e.x);
            o.writeFloat((float) e.y);
            o.writeByte(e.bot ? 0 : 1);
        }
        List<ClientConn> players = new ArrayList<>();
        for (ClientConn c : clients) {
            if (!c.dead && c.lastVisible != null) {
                players.add(c);
            }
        }
        o.writeInt(players.size());
        for (ClientConn c : players) {
            int[] vis = c.lastVisible;
            o.writeInt(c.avatar.id);
            o.writeInt(vis.length);
            for (int id : vis) {
                o.writeInt(id);
            }
            if (detail) {
                int[] hidden = occludedFor(c.avatar, vis, all);
                o.writeInt(hidden.length);
                for (int id : hidden) {
                    o.writeInt(id);
                }
            }
        }
        o.flush();
        return bytes.toByteArray();
    }

    /** Bots within the interest radius, not sent to the viewer, with a wall on the line of sight. */
    private int[] occludedFor(Entity viewer, int[] sent, List<Entity> all) {
        Obstacles obs = world.getObstacles();
        if (obs == null) {
            return new int[0];
        }
        java.util.Set<Integer> sentSet = new java.util.HashSet<>();
        for (int id : sent) {
            sentSet.add(id);
        }
        double r = interest.radius();
        double r2 = r * r;
        int[] out = new int[16];
        int n = 0;
        for (Entity e : all) {
            if (!e.bot || sentSet.contains(e.id)) {
                continue;
            }
            double dx = e.x - viewer.x;
            double dy = e.y - viewer.y;
            if (dx * dx + dy * dy <= r2 && !obs.lineOfSight(viewer.x, viewer.y, e.x, e.y)) {
                if (n == out.length) {
                    out = java.util.Arrays.copyOf(out, n * 2);
                }
                out[n++] = e.id;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** OBSTACLE_MAP frame for the current world (empty map when the world has no obstacles). */
    private byte[] buildObstacleMap() throws IOException {
        Obstacles obs = world.getObstacles();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeByte(Protocol.OBSTACLE_MAP);
        if (obs == null) {
            o.writeFloat(0f);
            o.writeInt(0);
            o.writeInt(0);
        } else {
            int cols = obs.cols();
            int rows = obs.rows();
            o.writeFloat((float) obs.cellSize());
            o.writeInt(cols);
            o.writeInt(rows);
            byte[] bits = new byte[(cols * rows + 7) / 8];
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    if (obs.blockedCell(x, y)) {
                        int idx = y * cols + x;
                        bits[idx >> 3] |= (byte) (1 << (idx & 7));
                    }
                }
            }
            o.write(bits);
        }
        o.flush();
        return bytes.toByteArray();
    }

    /** Sends OBSTACLE_RECTS once, right after the welcome message; nothing at all when the world has no obstacles. */
    private void writeObstacleRects(DataOutputStream out) throws IOException {
        Obstacles obs = world.getObstacles();
        if (obs != null) {
            Protocol.writeObstacleRects(out, obs.rectsAsFloats());
        }
    }

    private void report() {
        System.out.printf("clients=%d spectators=%d entities=%d  sent/s: entities=%d bytes=%d%n",
                clients.size(), spectators.size(), world.all().size(), entitiesSent.getAndSet(0), bytesSent.getAndSet(0));
    }

    private final class SpectatorConn {
        private final Socket socket;
        private final DataOutputStream out;
        private volatile boolean dead;
        /** Set once this spectator asked for SPECTATE_REQUEST_DETAIL; it then gets DETAIL_SNAPSHOT frames. */
        private volatile boolean detail;

        SpectatorConn(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            synchronized (out) {
                out.writeByte(Protocol.SPECTATE_WELCOME);
                out.writeFloat((float) World.SIZE);
                out.writeFloat((float) World.SIZE);
                out.writeFloat((float) interest.radius());
                writeObstacleRects(out);
                out.flush();
            }
            System.out.println("spectator connected");
        }

        /** Spectators may only send SPECTATE_REQUEST_DETAIL; any other byte is ignored. Reading also detects disconnect. */
        void startReader() {
            Thread t = new Thread(() -> {
                try {
                    int b;
                    while ((b = socket.getInputStream().read()) >= 0) {
                        if (b == Protocol.SPECTATE_REQUEST_DETAIL && !detail) {
                            byte[] map = buildObstacleMap();
                            synchronized (out) {
                                out.write(map);
                                out.flush();
                                detail = true;
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // disconnect
                } finally {
                    close();
                }
            }, "spectator-reader");
            t.setDaemon(true);
            t.start();
        }

        void send(byte[] frame) {
            if (dead) {
                return;
            }
            try {
                synchronized (out) {
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            if (dead) {
                return;
            }
            dead = true;
            spectators.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closing
            }
            System.out.println("spectator disconnected");
        }
    }

    private final class ClientConn {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final Entity avatar;
        private volatile boolean dead;
        /** Ids sent to this player in the most recent snapshot (read by the spectator broadcast, same tick thread). */
        private volatile int[] lastVisible;

        ClientConn(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            this.avatar = world.spawnPlayer();
            synchronized (out) {
                out.writeByte(Protocol.WELCOME);
                out.writeInt(avatar.id);
                out.writeFloat((float) World.SIZE);
                out.writeFloat((float) World.SIZE);
                out.writeFloat((float) interest.radius());
                writeObstacleRects(out);
                out.flush();
            }
            System.out.println("client " + avatar.id + " connected");
        }

        void startReader() {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        byte type = in.readByte();
                        if (type == Protocol.INPUT) {
                            float dx = in.readFloat();
                            float dy = in.readFloat();
                            world.setPlayerVelocity(avatar, dx, dy);
                        }
                    }
                } catch (IOException ignored) {
                    // disconnect
                } finally {
                    close();
                }
            }, "reader-" + avatar.id);
            t.setDaemon(true);
            t.start();
        }

        void sendSnapshot(int tick) {
            if (dead) {
                return;
            }
            try {
                List<Entity> visible = interest.visibleTo(avatar, world.all());
                int[] ids = new int[visible.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = visible.get(i).id;
                }
                lastVisible = ids;
                synchronized (out) {
                    out.writeByte(Protocol.SNAPSHOT);
                    out.writeInt(tick);
                    out.writeInt(visible.size());
                    for (Entity e : visible) {
                        out.writeInt(e.id);
                        out.writeFloat((float) e.x);
                        out.writeFloat((float) e.y);
                    }
                    out.flush();
                }
                entitiesSent.addAndGet(visible.size());
                bytesSent.addAndGet(9L + (long) visible.size() * Protocol.ENTITY_BYTES);
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            if (dead) {
                return;
            }
            dead = true;
            clients.remove(this);
            world.remove(avatar.id);
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closing
            }
            System.out.println("client " + avatar.id + " disconnected");
        }
    }
}
