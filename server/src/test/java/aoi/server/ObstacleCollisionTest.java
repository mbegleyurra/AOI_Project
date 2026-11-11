package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Obstacle layout, spawn validity, circle-vs-AABB collision, sliding, tunnelling and the segment query. */
class ObstacleCollisionTest {
    private static final double R = World.ENTITY_RADIUS;

    /** One solid 4x20-cell wall: x 500..540, y 400..600. */
    private static Obstacles thickWall() {
        Obstacles o = new Obstacles(10.0);
        o.addBuilding(50, 40, 4, 20, 0);
        return o;
    }

    /** One solid 1x20-cell wall (the thinnest possible): x 500..510, y 400..600. */
    private static Obstacles thinWall() {
        Obstacles o = new Obstacles(10.0);
        o.addBuilding(50, 40, 1, 20, 0);
        return o;
    }

    private static void assertNoneInside(World w, Obstacles o, String when) {
        for (Entity e : w.all()) {
            assertTrue(e.x >= 0 && e.x <= World.SIZE && e.y >= 0 && e.y <= World.SIZE,
                    when + ": entity " + e.id + " left the world at " + e.x + "," + e.y);
            assertFalse(o.isBlocked(e.x, e.y), when + ": entity " + e.id + " centre inside a wall at " + e.x + "," + e.y);
            assertFalse(o.circleOverlaps(e.x, e.y, R - 1e-6),
                    when + ": entity " + e.id + " overlaps an obstacle at " + e.x + "," + e.y);
        }
    }

    // ---- layout ----

    @Test
    void layoutHasBetweenFifteenAndThirtyRectsAndIsDeterministic() {
        for (long seed = 1; seed <= 50; seed++) {
            List<Obstacles.Rect> a = Obstacles.generateRects(seed).rects();
            assertTrue(a.size() >= 15 && a.size() <= 30, "seed " + seed + " produced " + a.size() + " obstacles");
            assertEquals(a, Obstacles.generateRects(seed).rects(), "same seed must give the same layout");
        }
        assertFalse(Obstacles.generateRects(1).rects().equals(Obstacles.generateRects(2).rects()),
                "different seeds should give different layouts");
    }

    @Test
    void freeSpaceIsOneConnectedRegion() {
        for (long seed = 1; seed <= 30; seed++) {
            Obstacles o = Obstacles.generateRects(seed);
            int free = 0;
            int sx = -1;
            int sy = -1;
            for (int y = 0; y < o.rows(); y++) {
                for (int x = 0; x < o.cols(); x++) {
                    if (!o.blockedCell(x, y)) {
                        free++;
                        sx = x;
                        sy = y;
                    }
                }
            }
            boolean[] seen = new boolean[o.cols() * o.rows()];
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            queue.add(new int[]{sx, sy});
            seen[sy * o.cols() + sx] = true;
            int reached = 0;
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                reached++;
                int[][] nb = {{c[0] + 1, c[1]}, {c[0] - 1, c[1]}, {c[0], c[1] + 1}, {c[0], c[1] - 1}};
                for (int[] n : nb) {
                    if (!o.blockedCell(n[0], n[1]) && !seen[n[1] * o.cols() + n[0]]) {
                        seen[n[1] * o.cols() + n[0]] = true;
                        queue.add(n);
                    }
                }
            }
            assertEquals(free, reached, "seed " + seed + ": free space is split into pockets (a spawn could be trapped)");
        }
    }

    @Test
    void rectsExactlyMirrorTheGridForTheLegacyHollowLayoutToo() {
        Obstacles o = Obstacles.generate(1L);
        for (int cy = 0; cy < o.rows(); cy++) {
            for (int cx = 0; cx < o.cols(); cx++) {
                double px = (cx + 0.5) * o.cellSize();
                double py = (cy + 0.5) * o.cellSize();
                boolean inRect = false;
                for (Obstacles.Rect r : o.rects()) {
                    if (px > r.x() && px < r.x() + r.w() && py > r.y() && py < r.y() + r.h()) {
                        inRect = true;
                        break;
                    }
                }
                assertEquals(o.blockedCell(cx, cy), inRect, "grid and rects disagree at cell " + cx + "," + cy);
            }
        }
    }

    @Test
    void mergedRectOfASolidBlockIsASingleRect() {
        assertEquals(List.of(new Obstacles.Rect(500, 400, 40, 200)), thickWall().rects());
    }

    // ---- spawns ----

    @Test
    void spawnsNeverLandInsideAnObstacle() {
        for (long seed = 1; seed <= 10; seed++) {
            Obstacles o = Obstacles.generateRects(seed);
            World w = new World(300, seed);
            w.setObstacles(o);
            assertNoneInside(w, o, "bots after setObstacles, seed " + seed);
            for (int i = 0; i < 200; i++) {
                Entity p = w.spawnPlayer();
                assertFalse(o.isBlocked(p.x, p.y), "player spawned in a wall");
                assertFalse(o.circleOverlaps(p.x, p.y, R - 1e-6), "player spawned overlapping an obstacle");
            }
        }
    }

    // ---- collision over time ----

    @Test
    void noEntityEndsUpInsideAnObstacleAfterManyTicks() {
        for (long seed : new long[]{1, 2, 3}) {
            Obstacles o = Obstacles.generateRects(seed);
            World w = new World(300, seed);
            w.setObstacles(o);
            List<Entity> players = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                players.add(w.spawnPlayer());
            }
            Random dirs = new Random(seed * 31);
            for (int tick = 0; tick < 1500; tick++) {
                if (tick % 40 == 0) {
                    for (Entity p : players) {
                        w.setPlayerVelocity(p, dirs.nextDouble() * 2 - 1, dirs.nextDouble() * 2 - 1);
                    }
                }
                w.step(1.0 / GameServer.TICK_HZ);
                assertNoneInside(w, o, "seed " + seed + " tick " + tick);
            }
        }
    }

    @Test
    void playerSlidesAlongAWallInsteadOfStopping() {
        Obstacles o = thickWall();
        World w = new World(0, 1L);
        w.setObstacles(o);
        Entity p = w.spawnPlayer();
        p.x = 480;
        p.y = 500;
        w.setPlayerVelocity(p, 1, 1); // diagonal into the wall's left face
        for (int i = 0; i < 10; i++) {
            w.step(1.0 / GameServer.TICK_HZ);
        }
        assertTrue(p.x <= 500 - R + 1e-6 && p.x >= 500 - R - 0.01,
                "x should rest against the face at " + (500 - R) + " but was " + p.x);
        assertTrue(p.y > 535, "y must keep advancing along the wall (slide), was " + p.y);
        assertFalse(o.circleOverlaps(p.x, p.y, R - 1e-6));
    }

    @Test
    void playerHeadOnAgainstAWallJustStops() {
        World w = new World(0, 1L);
        w.setObstacles(thickWall());
        Entity p = w.spawnPlayer();
        p.x = 480;
        p.y = 500;
        w.setPlayerVelocity(p, 1, 0);
        for (int i = 0; i < 30; i++) {
            w.step(1.0 / GameServer.TICK_HZ);
        }
        assertEquals(500 - R, p.x, 0.01);
        assertEquals(500, p.y, 1e-9);
    }

    @Test
    void noTunnellingThroughTheThinnestWallAtMaxSpeed() {
        Obstacles o = thinWall();
        World w = new World(0, 1L);
        w.setObstacles(o);
        Entity p = w.spawnPlayer();
        p.x = 450;
        p.y = 500;
        w.setPlayerVelocity(p, 1, 0); // 120 units/s = 6 per tick
        for (int i = 0; i < 100; i++) {
            w.step(1.0 / GameServer.TICK_HZ);
            assertTrue(p.x <= 500 - R + 1e-6, "tick " + i + ": avatar crossed into the wall, x=" + p.x);
        }
    }

    @Test
    void noTunnellingEvenWithAHugeTimeStep() {
        World w = new World(0, 1L);
        w.setObstacles(thinWall());
        Entity p = w.spawnPlayer();
        p.x = 450;
        p.y = 500;
        w.setPlayerVelocity(p, 1, 0);
        for (int i = 0; i < 20; i++) {
            w.step(0.5); // 60 units per step, six wall thicknesses
            assertTrue(p.x <= 500 - R + 1e-6, "step " + i + ": avatar tunnelled, x=" + p.x);
        }
    }

    @Test
    void botsBounceOffAnObstacleInsteadOfEnteringIt() {
        Obstacles o = thickWall();
        World w = new World(1, 3L);
        w.setObstacles(o);
        Entity bot = w.all().iterator().next();
        bot.x = 470;
        bot.y = 500;
        bot.vx = 40;
        bot.vy = 0;
        for (int i = 0; i < 200; i++) {
            w.step(1.0 / GameServer.TICK_HZ);
            assertFalse(o.circleOverlaps(bot.x, bot.y, R - 1e-6), "bot entered the wall at tick " + i);
        }
    }

    // ---- query API ----

    @Test
    void resolveCirclePushesACentreThatIsInsideOutThroughTheNearestSide() {
        Obstacles o = thickWall(); // x 500..540
        double[] p = {505, 500, 0, 0};
        assertTrue(o.resolveCircle(p, R));
        assertEquals(500 - R, p[0], 1e-3);
        assertEquals(500, p[1], 1e-9);
        assertEquals(-1, p[2], 1e-9);
        assertFalse(o.circleOverlaps(p[0], p[1], R - 1e-6));
    }

    @Test
    void resolveCircleLeavesAFreeCircleAlone() {
        double[] p = {300, 300, 9, 9};
        assertFalse(thickWall().resolveCircle(p, R));
        assertEquals(300, p[0]);
        assertEquals(300, p[1]);
        assertEquals(0, p[2]);
    }

    @Test
    void segmentBlockedHitsMissesAndCountsEndpointsInside() {
        Obstacles o = thickWall(); // x 500..540, y 400..600
        assertTrue(o.segmentBlocked(450, 500, 600, 500), "straight through");
        assertTrue(o.segmentBlocked(450, 450, 600, 560), "diagonal through");
        assertFalse(o.segmentBlocked(450, 500, 490, 500), "stops short");
        assertFalse(o.segmentBlocked(450, 390, 600, 390), "passes above");
        assertFalse(o.segmentBlocked(450, 610, 600, 610), "passes below");
        assertFalse(o.segmentBlocked(450, 500, 450, 700), "parallel, outside");
        assertTrue(o.segmentBlocked(520, 500, 700, 500), "starts inside");
        assertTrue(o.segmentBlocked(500, 300, 500, 700), "runs along the left face");
        assertFalse(new Obstacles(10.0).segmentBlocked(0, 0, 999, 999), "empty map blocks nothing");
    }
}
