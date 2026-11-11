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
import java.util.EnumSet;
import java.util.Set;

/** Top-down JavaFX view. WASD moves your avatar (green); what the server sends you is red. */
public final class ClientApp extends Application {
    private static final double VIEW = 800;

    private volatile Connection.Snapshot latest;
    private final Set<KeyCode> keys = EnumSet.noneOf(KeyCode.class);

    @Override
    public void start(Stage stage) throws IOException {
        var params = getParameters().getRaw();
        String host = params.size() > 0 ? params.get(0) : "localhost";
        int port = params.size() > 1 ? Integer.parseInt(params.get(1)) : Protocol.DEFAULT_PORT;

        Connection conn = new Connection(host, port);
        Thread reader = new Thread(() -> conn.readLoop(s -> latest = s), "net-reader");
        reader.setDaemon(true);
        reader.start();

        Canvas canvas = new Canvas(VIEW, VIEW);
        GraphicsContext g = canvas.getGraphicsContext2D();
        Scene scene = new Scene(new StackPane(canvas));
        scene.setOnKeyPressed(e -> keys.add(e.getCode()));
        scene.setOnKeyReleased(e -> keys.remove(e.getCode()));

        new AnimationTimer() {
            private float lastDx;
            private float lastDy;

            @Override
            public void handle(long now) {
                float dx = (keys.contains(KeyCode.D) ? 1 : 0) - (keys.contains(KeyCode.A) ? 1 : 0);
                float dy = (keys.contains(KeyCode.S) ? 1 : 0) - (keys.contains(KeyCode.W) ? 1 : 0);
                if (dx != lastDx || dy != lastDy) {
                    try {
                        conn.sendInput(dx, dy);
                    } catch (IOException ignored) {
                        // server gone
                    }
                    lastDx = dx;
                    lastDy = dy;
                }
                draw(g, conn);
            }
        }.start();

        stage.setTitle("AOI client #" + conn.clientId);
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

    private void draw(GraphicsContext g, Connection conn) {
        g.setFill(Color.web("#10131a"));
        g.fillRect(0, 0, VIEW, VIEW);
        Connection.Snapshot s = latest;
        if (s == null) {
            return;
        }
        double sx = VIEW / conn.worldW;
        double sy = VIEW / conn.worldH;
        float[] obstacles = conn.rects();
        if (obstacles != null) {
            g.setFill(Color.web("#323c52"));
            for (int i = 0; i < obstacles.length; i += 4) {
                g.fillRect(obstacles[i] * sx, obstacles[i + 1] * sy, obstacles[i + 2] * sx, obstacles[i + 3] * sy);
            }
        }
        for (int i = 0; i < s.ids().length; i++) {
            boolean me = s.ids()[i] == conn.clientId;
            if (me) {
                g.setStroke(Color.web("#3ddc84", 0.5));
                double r = conn.radius * sx;
                g.strokeOval(s.xs()[i] * sx - r, s.ys()[i] * sy - r, r * 2, r * 2);
            }
            g.setFill(me ? Color.web("#3ddc84") : Color.web("#ff5a5f"));
            double d = me ? 8 : 5;
            g.fillOval(s.xs()[i] * sx - d / 2, s.ys()[i] * sy - d / 2, d, d);
        }
        g.setFill(Color.WHITE);
        g.fillText("tick " + s.tick() + "  visible " + s.ids().length, 10, 16);
    }
}
