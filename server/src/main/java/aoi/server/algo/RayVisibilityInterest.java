package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;
import aoi.server.Obstacles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Entities within a radius AND in unobstructed line of sight.
 *
 * <p>Cost per viewer: one squared-distance test per entity in the world (broad phase, O(N)), then one
 * grid-traversal segment test per entity inside the radius. A segment test visits at most about
 * 2 * radius / cellSize cells (~30 for r=150, 10-unit cells). For 300 bots at r=150 in a 1000x1000 world that is
 * roughly 20 candidates per viewer, i.e. ~90k distance tests plus ~6k segment tests per tick for all viewers.
 *
 * <p>The viewer always sees itself, even when standing in a wall cell (the obstacle query reports a blocked
 * start cell as "no line of sight", which would otherwise drop the viewer from its own snapshot).
 */
public final class RayVisibilityInterest implements InterestManager {
    private final Obstacles obstacles;
    private final double radius;

    public RayVisibilityInterest(Obstacles obstacles, double radius) {
        this.obstacles = obstacles;
        this.radius = radius;
    }

    @Override
    public String name() {
        return "rayVisibility(r=" + radius + ")";
    }

    @Override
    public double radius() {
        return radius;
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        double r2 = radius * radius;
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            if (e.id == viewer.id) {
                out.add(e);
                continue;
            }
            double dx = e.x - viewer.x;
            double dy = e.y - viewer.y;
            if (dx * dx + dy * dy <= r2 && obstacles.lineOfSight(viewer.x, viewer.y, e.x, e.y)) {
                out.add(e);
            }
        }
        return out;
    }
}
