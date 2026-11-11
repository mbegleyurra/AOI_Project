package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;
import aoi.server.Obstacles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tile-level visibility: the set of grid cells visible from the viewer's cell (cell-to-cell Bresenham,
 * within the radius) is computed once per viewer cell and cached; entities are then tested by cell lookup.
 * Cheaper per entity than ray casting, coarser at wall edges. Assumes the Obstacles map is not mutated.
 */
public final class TileVisibilityInterest implements InterestManager {
    private final Obstacles obstacles;
    private final double radius;
    private final int cellRadius;
    private final int side;
    private final ConcurrentHashMap<Integer, boolean[]> cache = new ConcurrentHashMap<>();

    public TileVisibilityInterest(Obstacles obstacles, double radius) {
        this.obstacles = obstacles;
        this.radius = radius;
        this.cellRadius = (int) Math.ceil(radius / obstacles.cellSize());
        this.side = cellRadius * 2 + 1;
    }

    @Override
    public String name() {
        return "tileVisibility(r=" + radius + ")";
    }

    @Override
    public double radius() {
        return radius;
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        int vx = obstacles.cellX(viewer.x);
        int vy = obstacles.cellY(viewer.y);
        boolean[] vis = cache.computeIfAbsent(vy * obstacles.cols() + vx, k -> compute(vx, vy));
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            int ox = obstacles.cellX(e.x) - vx + cellRadius;
            int oy = obstacles.cellY(e.y) - vy + cellRadius;
            if (ox >= 0 && oy >= 0 && ox < side && oy < side && vis[oy * side + ox]) {
                out.add(e);
            }
        }
        return out;
    }

    private boolean[] compute(int vx, int vy) {
        boolean[] vis = new boolean[side * side];
        long r2 = (long) cellRadius * cellRadius;
        for (int oy = -cellRadius; oy <= cellRadius; oy++) {
            for (int ox = -cellRadius; ox <= cellRadius; ox++) {
                if ((long) ox * ox + (long) oy * oy > r2) {
                    continue;
                }
                int tx = vx + ox;
                int ty = vy + oy;
                if (tx < 0 || ty < 0 || tx >= obstacles.cols() || ty >= obstacles.rows()) {
                    continue;
                }
                vis[(oy + cellRadius) * side + ox + cellRadius] = cellsSee(vx, vy, tx, ty);
            }
        }
        return vis;
    }

    /** Bresenham between cell centres; intermediate wall cells block, the end cells do not. */
    private boolean cellsSee(int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        while (x != x1 || y != y1) {
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
            if ((x != x1 || y != y1) && obstacles.blockedCell(x, y)) {
                return false;
            }
        }
        return true;
    }
}
