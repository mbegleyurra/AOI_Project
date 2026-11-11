package aoi.server.algo;

import aoi.server.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform-grid spatial index shared by the tile-based algorithms. Rebuilt lazily from the entity
 * collection when it is older than maxAgeNanos or the entity count changed. Entities are
 * re-checked against their live position by callers where exactness matters; cell membership can
 * lag by up to maxAgeNanos (0 = rebuild every call, always exact).
 */
final class GridIndex {
    private final double tileSize;
    private final long maxAgeNanos;
    private final Map<Long, List<Entity>> cells = new HashMap<>();
    private long builtAt;
    private int builtCount = -1;

    GridIndex(double tileSize, long maxAgeNanos) {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0");
        }
        this.tileSize = tileSize;
        this.maxAgeNanos = maxAgeNanos;
    }

    int tileOf(double v) {
        return (int) Math.floor(v / tileSize);
    }

    static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xffffffffL);
    }

    synchronized void refresh(Collection<Entity> all) {
        long now = System.nanoTime();
        if (builtCount == all.size() && now - builtAt <= maxAgeNanos) {
            return;
        }
        cells.clear();
        int n = 0;
        for (Entity e : all) {
            cells.computeIfAbsent(key(tileOf(e.x), tileOf(e.y)), k -> new ArrayList<>()).add(e);
            n++;
        }
        builtCount = n;
        builtAt = now;
    }

    /** Entities indexed in tiles within `tiles` (Chebyshev) of the tile (cx, cy). */
    synchronized List<Entity> query(int cx, int cy, int tiles) {
        List<Entity> out = new ArrayList<>();
        for (int x = cx - tiles; x <= cx + tiles; x++) {
            for (int y = cy - tiles; y <= cy + tiles; y++) {
                List<Entity> c = cells.get(key(x, y));
                if (c != null) {
                    out.addAll(c);
                }
            }
        }
        return out;
    }
}
