package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;

import java.util.Collection;
import java.util.List;

/** Viewer's tile plus the 8 adjacent tiles (3x3 block), backed by the same grid index. */
public final class NeighborInterest implements InterestManager {
    private final double tileSize;
    private final GridIndex index;

    public NeighborInterest(double tileSize) {
        this(tileSize, 10);
    }

    public NeighborInterest(double tileSize, long indexMaxAgeMs) {
        this.tileSize = tileSize;
        this.index = new GridIndex(tileSize, indexMaxAgeMs * 1_000_000L);
    }

    @Override
    public String name() {
        return "neighbor(tile=" + tileSize + ")";
    }

    @Override
    public double radius() {
        return 1.5 * tileSize;
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        index.refresh(all);
        return index.query(index.tileOf(viewer.x), index.tileOf(viewer.y), 1);
    }
}
