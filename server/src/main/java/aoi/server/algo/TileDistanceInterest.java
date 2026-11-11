package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;

import java.util.Collection;
import java.util.List;

/**
 * Uniform grid: entities in tiles within `tiles` tiles (Chebyshev) of the viewer's tile. Uses a
 * spatial index that is rebuilt when older than indexMaxAgeMs (default 10 ms) or when the entity
 * count changes, so cell membership can lag slightly behind live positions. Pass indexMaxAgeMs=0
 * to rebuild on every call (exact, no speedup over a scan).
 */
public final class TileDistanceInterest implements InterestManager {
    private final double tileSize;
    private final int tiles;
    private final GridIndex index;

    public TileDistanceInterest(double tileSize, int tiles) {
        this(tileSize, tiles, 10);
    }

    public TileDistanceInterest(double tileSize, int tiles, long indexMaxAgeMs) {
        if (tiles < 0) {
            throw new IllegalArgumentException("tiles must be >= 0");
        }
        this.tileSize = tileSize;
        this.tiles = tiles;
        this.index = new GridIndex(tileSize, indexMaxAgeMs * 1_000_000L);
    }

    @Override
    public String name() {
        return "tile(size=" + tileSize + ",n=" + tiles + ")";
    }

    @Override
    public double radius() {
        return (tiles + 0.5) * tileSize;
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        index.refresh(all);
        return index.query(index.tileOf(viewer.x), index.tileOf(viewer.y), tiles);
    }
}
