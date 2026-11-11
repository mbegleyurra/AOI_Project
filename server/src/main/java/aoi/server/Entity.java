package aoi.server;

/** A simulated object. Bots wander randomly; player entities are driven by client input. */
public final class Entity {
    public final int id;
    public final boolean bot;
    public double x;
    public double y;
    public double vx;
    public double vy;

    public Entity(int id, boolean bot, double x, double y) {
        this.id = id;
        this.bot = bot;
        this.x = x;
        this.y = y;
    }
}
