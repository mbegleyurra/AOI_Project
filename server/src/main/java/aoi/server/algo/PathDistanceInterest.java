package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;
import aoi.server.Obstacles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Walking distance: BFS over free grid cells (8-connected, no corner cutting) from the viewer's cell;
 * entities whose cell is reachable within maxSteps cell moves are visible.
 */
public final class PathDistanceInterest implements InterestManager {
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private final Obstacles obstacles;
    private final int maxSteps;

    public PathDistanceInterest(Obstacles obstacles, int maxSteps) {
        this.obstacles = obstacles;
        this.maxSteps = maxSteps;
    }

    @Override
    public String name() {
        return "pathDistance(steps=" + maxSteps + ")";
    }

    @Override
    public double radius() {
        return maxSteps * obstacles.cellSize();
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        int cols = obstacles.cols();
        int rows = obstacles.rows();
        int[] dist = new int[cols * rows];
        java.util.Arrays.fill(dist, -1);
        int[] queue = new int[cols * rows];
        int head = 0;
        int tail = 0;
        int start = obstacles.cellY(viewer.y) * cols + obstacles.cellX(viewer.x);
        dist[start] = 0;
        queue[tail++] = start;
        while (head < tail) {
            int cur = queue[head++];
            int d = dist[cur];
            if (d >= maxSteps) {
                continue;
            }
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 8; k++) {
                int nx = cx + DX[k];
                int ny = cy + DY[k];
                if (obstacles.blockedCell(nx, ny) || dist[ny * cols + nx] >= 0) {
                    continue;
                }
                if (k >= 4 && (obstacles.blockedCell(cx + DX[k], cy) || obstacles.blockedCell(cx, cy + DY[k]))) {
                    continue;
                }
                dist[ny * cols + nx] = d + 1;
                queue[tail++] = ny * cols + nx;
            }
        }
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            if (dist[obstacles.cellY(e.y) * cols + obstacles.cellX(e.x)] >= 0) {
                out.add(e);
            }
        }
        return out;
    }
}
