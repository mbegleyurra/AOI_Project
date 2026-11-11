package aoi.server;

import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The simulated 2D world. Seeded so runs are repeatable for algorithm comparison. */
public final class World {
    public static final double SIZE = 1000.0;
    /** Collision radius of every entity (bots and players), in world units. */
    public static final double ENTITY_RADIUS = 3.0;
    private static final double BOT_SPEED = 40.0;
    private static final double PLAYER_SPEED = 120.0;

    private final Map<Integer, Entity> entities = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Random rnd;
    private volatile Obstacles obstacles;

    public World(int botCount, long seed) {
        this.rnd = new Random(seed);
        for (int i = 0; i < botCount; i++) {
            Entity bot = new Entity(nextId.getAndIncrement(), true, rnd.nextDouble() * SIZE, rnd.nextDouble() * SIZE);
            randomHeading(bot);
            entities.put(bot.id, bot);
        }
    }

    public Entity spawnPlayer() {
        synchronized (rnd) {
            Entity p = new Entity(nextId.getAndIncrement(), false, rnd.nextDouble() * SIZE, rnd.nextDouble() * SIZE);
            placeFree(p);
            entities.put(p.id, p);
            return p;
        }
    }

    public Obstacles getObstacles() {
        return obstacles;
    }

    /** Optional wall map; null (default) means open terrain. Entities already inside walls are moved to free space. */
    public void setObstacles(Obstacles obstacles) {
        synchronized (rnd) {
            this.obstacles = obstacles;
            if (obstacles != null) {
                for (Entity e : entities.values()) {
                    placeFree(e);
                }
            }
        }
    }

    /** Caller holds the rnd lock. No-op without obstacles. */
    private void placeFree(Entity e) {
        Obstacles o = obstacles;
        if (o == null) {
            return;
        }
        for (int i = 0; i < 1000 && (o.isBlocked(e.x, e.y) || o.circleOverlaps(e.x, e.y, ENTITY_RADIUS)); i++) {
            e.x = rnd.nextDouble() * SIZE;
            e.y = rnd.nextDouble() * SIZE;
        }
    }

    public void remove(int id) {
        entities.remove(id);
    }

    public Collection<Entity> all() {
        return entities.values();
    }

    public void setPlayerVelocity(Entity p, double dx, double dy) {
        double len = Math.hypot(dx, dy);
        if (len > 1e-9) {
            p.vx = dx / len * PLAYER_SPEED;
            p.vy = dy / len * PLAYER_SPEED;
        } else {
            p.vx = 0;
            p.vy = 0;
        }
    }

    /**
     * Advances every entity by dt. With obstacles, each entity is a circle of ENTITY_RADIUS: movement is
     * split into sub-steps of at most ENTITY_RADIUS/2 (so no step can tunnel through a rectangle) and each
     * sub-step is resolved circle-vs-AABB, which slides the entity along edges. Bots also reflect off the
     * surface; players keep their input velocity and simply slide.
     */
    public void step(double dt) {
        Obstacles o = obstacles;
        for (Entity e : entities.values()) {
            if (e.bot && rnd.nextDouble() < 0.02) {
                randomHeading(e);
            }
            int n = 1;
            if (o != null) {
                n = Math.max(1, (int) Math.ceil(Math.hypot(e.vx, e.vy) * dt / (ENTITY_RADIUS * 0.5)));
            }
            double sub = dt / n;
            double[] p = new double[4];
            for (int i = 0; i < n; i++) {
                double px = e.x;
                double py = e.y;
                e.x += e.vx * sub;
                e.y += e.vy * sub;
                if (e.x < 0 || e.x > SIZE) {
                    e.vx = -e.vx;
                    e.x = Math.max(0, Math.min(SIZE, e.x));
                }
                if (e.y < 0 || e.y > SIZE) {
                    e.vy = -e.vy;
                    e.y = Math.max(0, Math.min(SIZE, e.y));
                }
                if (o == null) {
                    continue;
                }
                p[0] = e.x;
                p[1] = e.y;
                if (!o.resolveCircle(p, ENTITY_RADIUS)) {
                    continue;
                }
                e.x = Math.max(0, Math.min(SIZE, p[0]));
                e.y = Math.max(0, Math.min(SIZE, p[1]));
                if (o.circleOverlaps(e.x, e.y, ENTITY_RADIUS - 1e-6)) {
                    // Pushed into a second obstacle or against the world edge: keep the last valid spot.
                    e.x = px;
                    e.y = py;
                    if (e.bot) {
                        e.vx = -e.vx;
                        e.vy = -e.vy;
                    }
                } else if (e.bot) {
                    double dot = e.vx * p[2] + e.vy * p[3];
                    if (dot < 0) {
                        e.vx -= 2 * dot * p[2];
                        e.vy -= 2 * dot * p[3];
                    }
                }
            }
        }
    }

    private void randomHeading(Entity e) {
        double a = rnd.nextDouble() * Math.PI * 2;
        e.vx = Math.cos(a) * BOT_SPEED;
        e.vy = Math.sin(a) * BOT_SPEED;
    }
}
