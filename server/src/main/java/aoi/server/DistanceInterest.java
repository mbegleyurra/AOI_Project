package aoi.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Baseline algorithm: everything within a circle of the given radius. */
public final class DistanceInterest implements InterestManager {
    private final double radius;

    public DistanceInterest(double radius) {
        this.radius = radius;
    }

    @Override
    public String name() {
        return "distance(r=" + radius + ")";
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
            double dx = e.x - viewer.x;
            double dy = e.y - viewer.y;
            if (dx * dx + dy * dy <= r2) {
                out.add(e);
            }
        }
        return out;
    }
}
