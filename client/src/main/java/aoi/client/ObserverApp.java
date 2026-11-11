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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Server full view vs. what one player receives.
 * Grey = every bot the server knows. Green = players with their interest-radius ring (selected player is bright).
 * Filled blue-grey rectangles = obstacles. For the SELECTED player: red = bots the server sends them,
 * dim orange = bots inside the radius but hidden by an obstacle (server-computed), faint red lines = sight lines.
 * Controls: click a player or press TAB to select, L toggles sight lines.
 * Args: [host] [spectatorPort]  (spectator port defaults to 7778).
 */
public final class ObserverApp extends Application {
    private static final double VIEW = 800;
    private static final Color BG = Color.web("#10131a");
    private static final Color WALL = Color.web("#323c52");
    private static final Color GREY = Color.web("#6a707c");
    private static final Color RED = Color.web("#ff5a5f");
    private static final Color ORANGE = Color.web("#b8742f");
    private static final Color GREEN = Color.web("#3ddc84");

    private volatile SpectatorConnection.FullSnapshot latest;

    // UI-thread state
    private int selectedPlayer = -1;
    private boolean sightLines = true;
    private boolean dirty = true;
    private int lastTick = -1;

    private SpectatorConnection.ObstacleMap cachedMap;
    private double[] wallRects = new double[0]; // x, y, w, h in world units, one run of cells per entry group

    @Override
    public void start(Stage stage) throws IOException {
        var params = getParameters().getRaw();
        String host = params.size() > 0 ? params.get(0) : "localhost";
        int port = params.size() > 1 ? Integer.parseInt(params.get(1)) : Protocol.DEFAULT_SPECTATOR_PORT;

        SpectatorConnection conn = new SpectatorConnection(host, port, true);
        Thread reader = new Thread(() -> conn.readLoop(s -> latest = s), "net-reader");
        reader.setDaemon(true);
        reader.start();

        Canvas canvas = new Canvas(VIEW, VIEW);
        GraphicsContext g = canvas.getGraphicsContext2D();
        Scene scene = new Scene(new StackPane(canvas));

        scene.setOnMouseClicked(e -> {
            SpectatorConnection.FullSnapshot s = latest;
            if (s == null) {
                return;
            }
            double sx = VIEW / conn.worldW;
            double sy = VIEW / conn.worldH;
            double best = 18 * 18;
            for (int i = 0; i < s.ids().length; i++) {
                if (!s.isPlayer()[i]) {
                    continue;
                }
                double dx = s.xs()[i] * sx - e.getX();
                double dy = s.ys()[i] * sy - e.getY();
                if (dx * dx + dy * dy < best) {
                    best = dx * dx + dy * dy;
                    selectedPlayer = s.ids()[i];
                    dirty = true;
                }
            }
        });
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.L) {
                sightLines = !sightLines;
                dirty = true;
            } else if (e.getCode() == KeyCode.TAB) {
                cycleSelection();
                dirty = true;
                e.consume();
            }
        });

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                SpectatorConnection.FullSnapshot s = latest;
                if (s != null && (dirty || s.tick() != lastTick)) {
                    lastTick = s.tick();
                    dirty = false;
                    draw(g, conn, s);
                }
            }
        }.start();

        stage.setTitle("AOI observer (server full view vs player view) " + host + ":" + port);
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
        SpectatorConnection.FullSnapshot s = latest;
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
    }

    /** Merges consecutive wall cells of a row into one rectangle each. */
    private void rebuildWallRects(SpectatorConnection.ObstacleMap m) {
        List<double[]> runs = new ArrayList<>();
        for (int y = 0; y < m.rows(); y++) {
            int x = 0;
            while (x < m.cols()) {
                if (!m.wall()[y * m.cols() + x]) {
                    x++;
                    continue;
                }
                int x0 = x;
                while (x < m.cols() && m.wall()[y * m.cols() + x]) {
                    x++;
                }
                runs.add(new double[]{x0 * m.cellSize(), y * m.cellSize(), (x - x0) * m.cellSize(), m.cellSize()});
            }
        }
        wallRects = new double[runs.size() * 4];
        for (int i = 0; i < runs.size(); i++) {
            System.arraycopy(runs.get(i), 0, wallRects, i * 4, 4);
        }
        cachedMap = m;
    }

    private void draw(GraphicsContext g, SpectatorConnection conn, SpectatorConnection.FullSnapshot s) {
        double sx = VIEW / conn.worldW;
        double sy = VIEW / conn.worldH;
        g.setFill(BG);
        g.fillRect(0, 0, VIEW, VIEW);

        // Obstacles as filled rectangles: the server's OBSTACLE_RECTS when received, else the cell-grid map.
        float[] serverRects = conn.rects();
        if (serverRects != null) {
            g.setFill(WALL);
            for (int i = 0; i < serverRects.length; i += 4) {
                g.fillRect(serverRects[i] * sx, serverRects[i + 1] * sy,
                        serverRects[i + 2] * sx, serverRects[i + 3] * sy);
            }
        } else {
            SpectatorConnection.ObstacleMap map = conn.map();
            if (map != null && map != cachedMap) {
                rebuildWallRects(map);
            }
            g.setFill(WALL);
            for (int i = 0; i < wallRects.length; i += 4) {
                g.fillRect(wallRects[i] * sx, wallRects[i + 1] * sy, wallRects[i + 2] * sx, wallRects[i + 3] * sy);
            }
        }

        // Resolve the selected player (fall back to the first one when none or gone).
        int sel = -1;
        for (int i = 0; i < s.playerIds().length; i++) {
            if (s.playerIds()[i] == selectedPlayer) {
                sel = i;
            }
        }
        if (sel < 0 && s.playerIds().length > 0) {
            sel = 0;
            selectedPlayer = s.playerIds()[0];
        }

        BitSet sent = new BitSet();
        BitSet hidden = new BitSet();
        double selX = 0;
        double selY = 0;
        if (sel >= 0) {
            for (int id : s.visible()[sel]) {
                sent.set(id);
            }
            if (s.occluded() != null) {
                for (int id : s.occluded()[sel]) {
                    hidden.set(id);
                }
            }
            for (int i = 0; i < s.ids().length; i++) {
                if (s.ids()[i] == selectedPlayer) {
                    selX = s.xs()[i];
                    selY = s.ys()[i];
                }
            }
        }
        double r = conn.radius;
        double r2 = r * r;

        // Sight lines under the dots.
        int botsInRadius = 0;
        int botsSent = 0;
        int botsHidden = 0;
        if (sel >= 0 && sightLines) {
            g.setStroke(Color.web("#ff5a5f", 0.22));
            g.setLineWidth(1);
            for (int i = 0; i < s.ids().length; i++) {
                if (!s.isPlayer()[i] && sent.get(s.ids()[i])) {
                    g.strokeLine(selX * sx, selY * sy, s.xs()[i] * sx, s.ys()[i] * sy);
                }
            }
        }

        // Bots: grey (outside / unrelated), orange (hidden by obstacle), red (sent to selected player).
        for (int i = 0; i < s.ids().length; i++) {
            if (s.isPlayer()[i]) {
                continue;
            }
            int id = s.ids()[i];
            double dx = s.xs()[i] - selX;
            double dy = s.ys()[i] - selY;
            if (sel >= 0 && dx * dx + dy * dy <= r2) {
                botsInRadius++;
            }
            Color c = GREY;
            if (sel >= 0 && sent.get(id)) {
                c = RED;
                botsSent++;
            } else if (sel >= 0 && hidden.get(id)) {
                c = ORANGE;
                botsHidden++;
            }
            g.setFill(c);
            g.fillOval(s.xs()[i] * sx - 2.5, s.ys()[i] * sy - 2.5, 5, 5);
        }

        // Players on top, with interest ring (selected one bright).
        double rr = r * sx;
        for (int i = 0; i < s.ids().length; i++) {
            if (!s.isPlayer()[i]) {
                continue;
            }
            boolean isSel = s.ids()[i] == selectedPlayer;
            double px = s.xs()[i] * sx;
            double py = s.ys()[i] * sy;
            g.setStroke(Color.web("#3ddc84", isSel ? 0.95 : 0.35));
            g.setLineWidth(isSel ? 2 : 1);
            g.strokeOval(px - rr, py - rr, rr * 2, rr * 2);
            g.setFill(GREEN);
            g.fillOval(px - 4, py - 4, 8, 8);
        }
        g.setLineWidth(1);

        // Counters (for the selected player).
        g.setFill(Color.WHITE);
        double y = 16;
        g.fillText("tick " + s.tick() + "   players " + s.playerIds().length
                + (sel >= 0 ? "   selected: player " + selectedPlayer : "   (no player connected)"), 10, y);
        y += 15;
        g.fillText("total entities " + s.ids().length + "   in radius " + botsInRadius
                + "   sent to player " + botsSent + "   occluded " + botsHidden
                + (s.occluded() == null ? "   (no occlusion data from server)" : ""), 10, y);

        drawLegend(g);
    }

    private void drawLegend(GraphicsContext g) {
        String[] labels = {
            "all bots (server full view)", "sent to selected player", "in radius, hidden by obstacle",
            "player + interest radius", "obstacle", "sight line (L toggles)  |  click / TAB: select player"
        };
        Color[] colors = {GREY, RED, ORANGE, GREEN, WALL, Color.web("#ff5a5f", 0.5)};
        double h = labels.length * 16 + 8;
        double top = VIEW - h - 8;
        g.setFill(Color.web("#000000", 0.55));
        g.fillRect(8, top, 330, h);
        for (int i = 0; i < labels.length; i++) {
            double ly = top + 8 + i * 16;
            g.setFill(colors[i]);
            g.fillRect(14, ly, 10, 10);
            g.setFill(Color.WHITE);
            g.fillText(labels[i], 32, ly + 10);
        }
    }
}
