package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Latency baseline: every entity is visible to every client, every tick. The client therefore already knows where
 * everyone is and a "new entity" never has to wait for the server to announce it (0 ms notification delay).
 * radius() is only what clients may draw as the AOI circle; it does not filter anything.
 */
public final class AllInterest implements InterestManager {
    /** Larger than the world diagonal (1000 x 1000). */
    public static final double DEFAULT_RADIUS = 1500.0;

    private final double radius;

    public AllInterest() {
        this(DEFAULT_RADIUS);
    }

    public AllInterest(double radius) {
        this.radius = radius > 0 ? radius : DEFAULT_RADIUS;
    }

    @Override
    public String name() {
        return "all";
    }

    @Override
    public double radius() {
        return radius;
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        return new ArrayList<>(all);
    }
}
