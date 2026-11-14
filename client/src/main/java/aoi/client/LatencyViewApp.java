package aoi.client;

import aoi.common.Protocol;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Side-by-side latency view for ONE player, fed by the server's spectator stream.
 * LEFT  = the client already knows where the other players are (baseline, no waiting).
 * RIGHT = the client only learns about them when the server sends them (AOI), after a simulated one-way delay.
 * Entities that are visible on the left but not yet known on the right are hollow yellow rings.
 * Every time an entity becomes visible on the left, we time how long until it shows up on the right.
 * Controls: TAB = next player, UP/DOWN = delay +/-20 ms, 0 = delay 0, 4 = delay 40.
 * Args: [host] [spectatorPort] [delayMs]
 */
public final class LatencyViewApp extends Application {
    private static final double W = 1180;
    private static final double H = 760;
    private static final double PANEL = 540;
    private static final double LX = 30;
    private static final double RX = 610;
    private static final double PY = 70;
    private static final Color BG = Color.web("#10131a");
    private static final Color WALL = Color.web("#323c52");
    private static final Color KNOWN = Color.web("#ff5a5f");
    private static final Color MISSING = Color.web("#ffd24a");
    private static final Color GREEN = Color.web("#3ddc84");
    private static final int SAMPLE_CAP = 120;

    private record Entry(long t, SpectatorConnection.FullSnapshot s) { }

    /** A snapshot seen from one player: that player's position and the entities the server says they can see. */
    private static final class View {
        final SpectatorConnection.FullSnapshot s;
        final BitSet visible = new BitSet();
        final Map<Integer, Integer> idx = new HashMap<>();
        double px;
        double py;
        boolean hasPlayer;

        View(SpectatorConnection.FullSnapshot s, int playerId) {
            this.s = s;
            for (int i = 0; i < s.ids().length; i++) {
                idx.put(s.ids()[i], i);
            }
            Integer pi = idx.get(playerId);
            if (pi != null) {
                hasPlayer = true;
                px = s.xs()[pi];
                py = s.ys()[pi];
            }
            for (int k = 0; k < s.playerIds().length; k++) {
                if (s.playerIds()[k] == playerId) {
                    for (int id : s.visible()[k]) {
                        visible.set(id);
                    }
                }
            }
        }
    }

    private final ArrayDeque<Entry> buffer = new ArrayDeque<>();
    private final Object lock = new Object();

    private int selectedPlayer = -1;
    private long delayMs = 100;
    private BitSet prevTruth = new BitSet();
    private BitSet prevKnown = new BitSet();
    private final Map<Integer, Long> pending = new HashMap<>();
    private final Map<Integer, Long> flashLeft = new HashMap<>();
    private final Map<Integer, Long> flashRight = new HashMap<>();
    private final ArrayDeque<Double> samples = new ArrayDeque<>();

    @Override
    public void start(Stage stage) throws IOException {
        var params = getParameters().getRaw();
        String host = params.size() > 0 ? params.get(0) : "localhost";
        int port = params.size() > 1 ? Integer.parseInt(params.get(1)) : Protocol.DEFAULT_SPECTATOR_PORT;
        if (params.size() > 2) {
            delayMs = Long.parseLong(params.get(2));
        }

        SpectatorConnection conn = new SpectatorConnection(host, port, true);
        Thread reader = new Thread(() -> conn.readLoop(s -> {
            long t = System.nanoTime();
            synchronized (lock) {
                buffer.addLast(new Entry(t, s));
                while (buffer.size() > 1 && t - buffer.peekFirst().t() > 5_000_000_000L) {
                    buffer.pollFirst();
                }
            }
        }), "net-reader");
        reader.setDaemon(true);
        reader.start();

        Canvas canvas = new Canvas(W, H);
        GraphicsContext g = canvas.getGraphicsContext2D();
        Scene scene = new Scene(new StackPane(canvas));
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP) {
                delayMs = Math.min(1000, delayMs + 20);
            } else if (e.getCode() == KeyCode.DOWN) {
                delayMs = Math.max(0, delayMs - 20);
            } else if (e.getCode() == KeyCode.DIGIT0) {
                delayMs = 0;
            } else if (e.getCode() == KeyCode.DIGIT4) {
                delayMs = 40;
            } else if (e.getCode() == KeyCode.TAB) {
                cycleSelection();
                e.consume();
            }
        });

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                frame(g, conn, now);
            }
        }.start();

        stage.setTitle("AOI latency view: client already knows vs waits for server  " + host + ":" + port);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            try {
                conn.close();
            } catch (IOException ignored) {
                // closing
            }
        });
        stage.show();
    }

    private void cycleSelection() {
        SpectatorConnection.FullSnapshot s = newest();
        if (s == null || s.playerIds().length == 0) {
            return;
        }
        int[] p = s.playerIds();
        int idx = -1;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == selectedPlayer) {
                idx = i;
            }
        }
        selectedPlayer = p[(idx + 1) % p.length];
        resetTracking();
    }

    private void resetTracking() {
        prevTruth = new BitSet();
        prevKnown = new BitSet();
        pending.clear();
        flashLeft.clear();
        flashRight.clear();
    }

    private SpectatorConnection.FullSnapshot newest() {
        synchronized (lock) {
            Entry e = buffer.peekLast();
            return e == null ? null : e.s();
        }
    }

    private void frame(GraphicsContext g, SpectatorConnection conn, long now) {
        SpectatorConnection.FullSnapshot latest;
        SpectatorConnection.FullSnapshot delayed = null;
        synchronized (lock) {
            Entry last = buffer.peekLast();
            if (last == null) {
                g.setFill(BG);
                g.fillRect(0, 0, W, H);
                g.setFill(Color.WHITE);
                g.fillText("waiting for the server spectator stream...", 20, 30);
                return;
            }
            latest = last.s();
            long target = now - delayMs * 1_000_000L;
            var it = buffer.descendingIterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.t() <= target) {
                    delayed = e.s();
                    break;
                }
            }
        }
        if (latest.playerIds().length == 0) {
            g.setFill(BG);
            g.fillRect(0, 0, W, H);
            g.setFill(Color.WHITE);
            g.fillText("connected to the server, but no player is connected yet (the view follows one player)...", 20, 30);
            return;
        }
        boolean found = false;
        for (int id : latest.playerIds()) {
            if (id == selectedPlayer) {
                found = true;
            }
        }
        if (!found) {
            selectedPlayer = latest.playerIds()[0];
            resetTracking();
        }

        View truth = new View(latest, selectedPlayer);
        View known = delayed == null ? null : new View(delayed, selectedPlayer);
        BitSet knownSet = known == null ? new BitSet() : known.visible;

        // Track entities entering the player's view and how long the right panel takes to learn of them.
        for (int id = truth.visible.nextSetBit(0); id >= 0; id = truth.visible.nextSetBit(id + 1)) {
            if (!prevTruth.get(id)) {
                flashLeft.put(id, now);
                if (!knownSet.get(id)) {
                    pending.put(id, now);
                }
            }
        }
        for (int id = knownSet.nextSetBit(0); id >= 0; id = knownSet.nextSetBit(id + 1)) {
            if (!prevKnown.get(id)) {
                flashRight.put(id, now);
            }
            Long t0 = pending.remove(id);
            if (t0 != null) {
                samples.addLast((now - t0) / 1e6);
                while (samples.size() > SAMPLE_CAP) {
                    samples.pollFirst();
                }
            }
        }
        pending.keySet().removeIf(id -> !truth.visible.get(id));
        prevTruth = (BitSet) truth.visible.clone();
        prevKnown = (BitSet) knownSet.clone();

        g.setFill(BG);
        g.fillRect(0, 0, W, H);
        g.setFill(Color.WHITE);
        g.fillText("AOI latency: what the player sees if the client already knows vs waits for the server"
                + "      player " + selectedPlayer + "   tick " + latest.tick(), 30, 24);
        g.setFill(Color.web("#9aa3b5"));
        g.fillText("TAB next player   UP/DOWN delay +/-20 ms   0 = no delay   4 = 40 ms", 30, 44);

        // Left: client already knows. Right: client waits for server data.
        drawPanel(g, conn, LX, truth, truth, null, flashLeft, now,
                "CLIENT ALREADY KNOWS  (baseline, no waiting)");
        BitSet missing = (BitSet) truth.visible.clone();
        missing.andNot(knownSet);
        drawPanel(g, conn, RX, truth, known, missing, flashRight, now,
                "CLIENT WAITS FOR SERVER  (" + delayMs + " ms one-way delay)");

        drawStats(g, truth.visible.cardinality(), knownSet.cardinality(), missing.cardinality());
    }

    /**
     * Draws one panel centred on the selected player's true position. entities = the view whose entities are drawn
     * (at their own positions). missing = entities the player can see but the client does not know yet.
     */
    private void drawPanel(GraphicsContext g, SpectatorConnection conn, double ox, View centre, View entities,
                           BitSet missing, Map<Integer, Long> flash, long now, String title) {
        double span = conn.radius * 2.5;
        double scale = PANEL / span;
        double cx = ox + PANEL / 2;
        double cy = PY + PANEL / 2;
        double wx0 = centre.px - span / 2;
        double wy0 = centre.py - span / 2;

        g.setFill(Color.WHITE);
        g.fillText(title, ox, PY - 8);
        g.save();
        g.beginPath();
        g.rect(ox, PY, PANEL, PANEL);
        g.clip();
        g.setFill(Color.web("#0b0e14"));
        g.fillRect(ox, PY, PANEL, PANEL);

        float[] rects = conn.rects();
        g.setFill(WALL);
        if (rects != null) {
            for (int i = 0; i + 3 < rects.length; i += 4) {
                g.fillRect(ox + (rects[i] - wx0) * scale, PY + (rects[i + 1] - wy0) * scale,
                        rects[i + 2] * scale, rects[i + 3] * scale);
            }
        } else {
            SpectatorConnection.ObstacleMap m = conn.map();
            if (m != null) {
                double cs = m.cellSize();
                for (int y = 0; y < m.rows(); y++) {
                    for (int x = 0; x < m.cols(); x++) {
                        if (m.wall()[y * m.cols() + x]) {
                            g.fillRect(ox + (x * cs - wx0) * scale, PY + (y * cs - wy0) * scale,
                                    cs * scale + 0.5, cs * scale + 0.5);
                        }
                    }
                }
            }
        }

        // Interest radius ring around the player.
        double rr = conn.radius * scale;
        g.setStroke(Color.web("#3ddc84", 0.5));
        g.setLineWidth(1.5);
        g.strokeOval(cx - rr, cy - rr, rr * 2, rr * 2);

        // Entities the client knows about.
        if (entities != null) {
            g.setFill(KNOWN);
            for (int id = entities.visible.nextSetBit(0); id >= 0; id = entities.visible.nextSetBit(id + 1)) {
                Integer i = entities.idx.get(id);
                if (i == null) {
                    continue;
                }
                double x = ox + (entities.s.xs()[i] - wx0) * scale;
                double y = PY + (entities.s.ys()[i] - wy0) * scale;
                g.fillOval(x - 3.5, y - 3.5, 7, 7);
                Long ft = flash.get(id);
                if (ft != null) {
                    double age = (now - ft) / 1e9;
                    if (age < 0.6) {
                        double rad = 5 + age * 40;
                        g.setStroke(Color.web("#ffffff", 1 - age / 0.6));
                        g.setLineWidth(2);
                        g.strokeOval(x - rad, y - rad, rad * 2, rad * 2);
                    } else {
                        flash.remove(id);
                    }
                }
            }
        }

        // Entities that exist for the player but the client has not been told about yet.
        if (missing != null) {
            g.setStroke(MISSING);
            g.setLineWidth(1.8);
            for (int id = missing.nextSetBit(0); id >= 0; id = missing.nextSetBit(id + 1)) {
                Integer i = centre.idx.get(id);
                if (i == null) {
                    continue;
                }
                double x = ox + (centre.s.xs()[i] - wx0) * scale;
                double y = PY + (centre.s.ys()[i] - wy0) * scale;
                g.strokeOval(x - 6, y - 6, 12, 12);
            }
        }

        // The player.
        g.setFill(GREEN);
        g.fillOval(cx - 6, cy - 6, 12, 12);
        g.restore();
        g.setStroke(Color.web("#3a4256"));
        g.setLineWidth(1);
        g.strokeRect(ox, PY, PANEL, PANEL);
    }

    private void drawStats(GraphicsContext g, int visibleNow, int knownCount, int missingCount) {
        double y0 = PY + PANEL + 28;
        g.setFill(Color.WHITE);
        g.fillText("entities in player's view now: " + visibleNow
                + "     client knows: " + knownCount
                + "     not known yet (hollow yellow rings): " + missingCount, LX, y0);

        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        String line;
        if (sorted.isEmpty()) {
            line = "new-entity delay: waiting for samples";
        } else {
            double sum = 0;
            for (double d : sorted) {
                sum += d;
            }
            line = String.format("new-entity delay (left shows it at 0 ms, right after):  mean %.1f ms   median %.1f ms"
                            + "   last %.1f ms   n=%d",
                    sum / sorted.size(), sorted.get(sorted.size() / 2), samples.peekLast(), sorted.size());
        }
        g.fillText(line, LX, y0 + 20);

        // Bar chart of recent delays.
        double cx = LX;
        double cw = W - 2 * LX;
        double cy = y0 + 34;
        double ch = H - cy - 12;
        g.setFill(Color.web("#0b0e14"));
        g.fillRect(cx, cy, cw, ch);
        double max = Math.max(60, delayMs * 1.6);
        for (double d : samples) {
            max = Math.max(max, d * 1.1);
        }
        int n = samples.size();
        double bw = cw / SAMPLE_CAP;
        int k = 0;
        g.setFill(KNOWN);
        for (double d : samples) {
            double bh = Math.min(ch, d / max * ch);
            g.fillRect(cx + (SAMPLE_CAP - n + k) * bw, cy + ch - bh, Math.max(1, bw - 1), bh);
            k++;
        }
        g.setStroke(Color.web("#3ddc84", 0.7));
        double ly = cy + ch - delayMs / max * ch;
        g.strokeLine(cx, ly, cx + cw, ly);
        g.setFill(Color.web("#9aa3b5"));
        g.fillText("each bar = one entity entering view: time until the waiting client learned of it"
                + "  (green line = " + delayMs + " ms simulated delay; the baseline on the left is always 0)",
                cx + 6, cy + 14);
    }
}
