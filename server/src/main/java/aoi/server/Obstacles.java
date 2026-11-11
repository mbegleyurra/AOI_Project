package aoi.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The server's single authoritative obstacle model. Storage is a grid of wall cells; the same data is
 * exposed as axis-aligned rectangles (rects()) for collision, spawn checks, ray queries and the wire
 * protocol. Rectangles are always derived from the grid (merged runs of cells), so grid queries
 * (isBlocked, lineOfSight, blockedCell) and rectangle queries (circleOverlaps, resolveCircle,
 * segmentBlocked) can never disagree. Treat an instance as immutable once it is handed to a World.
 */
public final class Obstacles {
    /** Axis-aligned obstacle rectangle in world units: covers [x, x+w] x [y, y+h]. */
    public record Rect(double x, double y, double w, double h) {
    }

    private static final double EPS = 1e-7;

    private final double cellSize;
    private final int cols;
    private final int rows;
    private final boolean[] wall;
    private volatile List<Rect> rects;

    /** Empty map. cellSize is the edge length of one grid cell in world units. */
    public Obstacles(double cellSize) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be positive");
        }
        this.cellSize = cellSize;
        this.cols = (int) Math.ceil(World.SIZE / cellSize);
        this.rows = cols;
        this.wall = new boolean[cols * rows];
    }

    /**
     * Seeded generation of hollow, building-like rectangles, each with a two-cell door on one side.
     * Rectangles smaller than 5 cells on a side are solid. Sizes are in cells.
     */
    public static Obstacles generate(long seed, int buildings, double cellSize, int minCells, int maxCells) {
        Obstacles o = new Obstacles(cellSize);
        Random r = new Random(seed);
        int span = Math.max(1, maxCells - minCells + 1);
        for (int i = 0; i < buildings; i++) {
            int w = minCells + r.nextInt(span);
            int h = minCells + r.nextInt(span);
            if (w >= o.cols || h >= o.rows) {
                continue;
            }
            int x0 = r.nextInt(o.cols - w);
            int y0 = r.nextInt(o.rows - h);
            o.addBuilding(x0, y0, w, h, r.nextInt(4));
        }
        return o;
    }

    /** Default layout: 10-unit cells, 60 buildings of 5..14 cells per side. */
    public static Obstacles generate(long seed) {
        return generate(seed, 60, 10.0, 5, 14);
    }

    /** Package-private so tests can build hand-made maps; not for use after the map is handed to a World. */
    void addBuilding(int x0, int y0, int w, int h, int doorSide) {
        rects = null;
        boolean solid = w < 5 || h < 5;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                boolean edge = x == x0 || x == x0 + w - 1 || y == y0 || y == y0 + h - 1;
                if (solid || edge) {
                    wall[y * cols + x] = true;
                }
            }
        }
        if (solid) {
            return;
        }
        int midX = x0 + w / 2;
        int midY = y0 + h / 2;
        switch (doorSide) {
            case 0 -> { clear(midX, y0); clear(midX + 1, y0); }
            case 1 -> { clear(midX, y0 + h - 1); clear(midX + 1, y0 + h - 1); }
            case 2 -> { clear(x0, midY); clear(x0, midY + 1); }
            default -> { clear(x0 + w - 1, midY); clear(x0 + w - 1, midY + 1); }
        }
    }

    private void clear(int cx, int cy) {
        if (cx >= 0 && cy >= 0 && cx < cols && cy < rows) {
            wall[cy * cols + cx] = false;
        }
    }

    /**
     * Seeded layout of {@code count} solid, separated rectangles (the game layout). Rectangles are
     * minCells..maxCells cells per side, at least gapCells cells apart from each other and from the
     * world edge, so the free space stays connected and no spawn can be walled in.
     * Fewer than {@code count} are returned only if the world is too crowded to place them.
     */
    public static Obstacles generateRects(long seed, int count, double cellSize, int minCells, int maxCells,
                                          int gapCells) {
        Obstacles o = new Obstacles(cellSize);
        Random r = new Random(seed);
        int span = Math.max(1, maxCells - minCells + 1);
        List<int[]> placed = new ArrayList<>();
        for (int attempt = 0; attempt < count * 200 && placed.size() < count; attempt++) {
            int w = minCells + r.nextInt(span);
            int h = minCells + r.nextInt(span);
            int roomX = o.cols - w - 2 * gapCells;
            int roomY = o.rows - h - 2 * gapCells;
            if (roomX < 0 || roomY < 0) {
                continue;
            }
            int x0 = gapCells + r.nextInt(roomX + 1);
            int y0 = gapCells + r.nextInt(roomY + 1);
            boolean clash = false;
            for (int[] p : placed) {
                if (x0 < p[0] + p[2] + gapCells && p[0] < x0 + w + gapCells
                        && y0 < p[1] + p[3] + gapCells && p[1] < y0 + h + gapCells) {
                    clash = true;
                    break;
                }
            }
            if (clash) {
                continue;
            }
            placed.add(new int[]{x0, y0, w, h});
            o.fillCells(x0, y0, w, h);
        }
        return o;
    }

    /** Game layout: 24 solid rectangles of 4..12 cells (40..120 units), 10-unit cells, 3-cell gaps. */
    public static Obstacles generateRects(long seed) {
        return generateRects(seed, 24, 10.0, 4, 12, 3);
    }

    private void fillCells(int x0, int y0, int w, int h) {
        rects = null;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                wall[y * cols + x] = true;
            }
        }
    }

    /** Immutable rectangle view of the map (merged cell runs). Computed once, then cached. */
    public List<Rect> rects() {
        List<Rect> r = rects;
        if (r == null) {
            r = List.copyOf(buildRects());
            rects = r;
        }
        return r;
    }

    /** Flat x,y,w,h per rectangle, as sent on the wire. */
    public float[] rectsAsFloats() {
        List<Rect> list = rects();
        float[] out = new float[list.size() * 4];
        for (int i = 0; i < list.size(); i++) {
            Rect r = list.get(i);
            out[i * 4] = (float) r.x();
            out[i * 4 + 1] = (float) r.y();
            out[i * 4 + 2] = (float) r.w();
            out[i * 4 + 3] = (float) r.h();
        }
        return out;
    }

    private List<Rect> buildRects() {
        List<Rect> out = new ArrayList<>();
        Map<Long, Integer> open = new HashMap<>();
        for (int y = 0; y <= rows; y++) {
            Map<Long, Integer> next = new HashMap<>();
            if (y < rows) {
                int x = 0;
                while (x < cols) {
                    if (!wall[y * cols + x]) {
                        x++;
                        continue;
                    }
                    int start = x;
                    while (x < cols && wall[y * cols + x]) {
                        x++;
                    }
                    long key = start * 100000L + (x - start);
                    next.put(key, open.getOrDefault(key, y));
                }
            }
            for (Map.Entry<Long, Integer> e : open.entrySet()) {
                if (!next.containsKey(e.getKey())) {
                    long key = e.getKey();
                    int x0 = (int) (key / 100000L);
                    int len = (int) (key % 100000L);
                    int y0 = e.getValue();
                    out.add(new Rect(x0 * cellSize, y0 * cellSize, len * cellSize, (y - y0) * cellSize));
                }
            }
            open = next;
        }
        return out;
    }

    /** True if a circle of radius r at (cx,cy) overlaps (not merely touches) any rectangle. */
    public boolean circleOverlaps(double cx, double cy, double r) {
        for (Rect b : rects()) {
            double dx = cx - Math.max(b.x(), Math.min(cx, b.x() + b.w()));
            double dy = cy - Math.max(b.y(), Math.min(cy, b.y() + b.h()));
            if (dx * dx + dy * dy < r * r) {
                return true;
            }
        }
        return false;
    }

    /**
     * Circle-vs-AABB resolution. p = {x, y, nx, ny}: on entry the circle centre; on return the centre
     * pushed out of every rectangle along the contact normal (so motion along an edge is preserved,
     * i.e. the circle slides), with the summed contact normal in p[2], p[3] (zero if nothing was hit).
     * A centre already inside a rectangle is pushed out through the nearest side.
     *
     * @return true if the position changed
     */
    public boolean resolveCircle(double[] p, double r) {
        p[2] = 0;
        p[3] = 0;
        boolean moved = false;
        for (int pass = 0; pass < 4; pass++) {
            boolean any = false;
            for (Rect b : rects()) {
                double cx = p[0];
                double cy = p[1];
                double nx = Math.max(b.x(), Math.min(cx, b.x() + b.w()));
                double ny = Math.max(b.y(), Math.min(cy, b.y() + b.h()));
                double dx = cx - nx;
                double dy = cy - ny;
                double d2 = dx * dx + dy * dy;
                if (d2 >= r * r) {
                    continue;
                }
                double ux;
                double uy;
                double push;
                if (d2 > 0) {
                    double d = Math.sqrt(d2);
                    ux = dx / d;
                    uy = dy / d;
                    push = r - d + EPS;
                } else {
                    double left = cx - b.x();
                    double right = b.x() + b.w() - cx;
                    double top = cy - b.y();
                    double bottom = b.y() + b.h() - cy;
                    double m = Math.min(Math.min(left, right), Math.min(top, bottom));
                    ux = 0;
                    uy = 0;
                    if (m == left) {
                        ux = -1;
                    } else if (m == right) {
                        ux = 1;
                    } else if (m == top) {
                        uy = -1;
                    } else {
                        uy = 1;
                    }
                    push = m + r + EPS;
                }
                p[0] = cx + ux * push;
                p[1] = cy + uy * push;
                p[2] += ux;
                p[3] += uy;
                any = true;
                moved = true;
            }
            if (!any) {
                break;
            }
        }
        double len = Math.hypot(p[2], p[3]);
        if (len > 1e-9) {
            p[2] /= len;
            p[3] /= len;
        }
        return moved;
    }

    /**
     * Exact segment query for visibility logic: true if the segment (x1,y1)-(x2,y2) touches any
     * obstacle rectangle (closed rectangles; an endpoint inside or on an obstacle counts as blocked).
     * Cost is O(rects()) per call.
     */
    public boolean segmentBlocked(double x1, double y1, double x2, double y2) {
        for (Rect b : rects()) {
            if (segmentHits(b, x1, y1, x2, y2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentHits(Rect b, double x1, double y1, double x2, double y2) {
        double t0 = 0;
        double t1 = 1;
        double dx = x2 - x1;
        double dy = y2 - y1;
        double[] origin = {x1, y1};
        double[] dir = {dx, dy};
        double[] lo = {b.x(), b.y()};
        double[] hi = {b.x() + b.w(), b.y() + b.h()};
        for (int a = 0; a < 2; a++) {
            if (Math.abs(dir[a]) < 1e-12) {
                if (origin[a] < lo[a] || origin[a] > hi[a]) {
                    return false;
                }
            } else {
                double ta = (lo[a] - origin[a]) / dir[a];
                double tb = (hi[a] - origin[a]) / dir[a];
                if (ta > tb) {
                    double tmp = ta;
                    ta = tb;
                    tb = tmp;
                }
                t0 = Math.max(t0, ta);
                t1 = Math.min(t1, tb);
                if (t0 > t1) {
                    return false;
                }
            }
        }
        return true;
    }

    public double cellSize() {
        return cellSize;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public int cellX(double x) {
        return Math.min(cols - 1, Math.max(0, (int) (x / cellSize)));
    }

    public int cellY(double y) {
        return Math.min(rows - 1, Math.max(0, (int) (y / cellSize)));
    }

    /** True for wall cells; cells outside the grid count as blocked. */
    public boolean blockedCell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) {
            return true;
        }
        return wall[cy * cols + cx];
    }

    /** True if the point is inside a wall cell or outside the world. */
    public boolean isBlocked(double x, double y) {
        if (x < 0 || y < 0 || x > World.SIZE || y > World.SIZE) {
            return true;
        }
        return wall[cellY(y) * cols + cellX(x)];
    }

    public int blockedCount() {
        int n = 0;
        for (boolean b : wall) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /** True if no wall cell lies on the segment between the two points (grid traversal, endpoints included). */
    public boolean lineOfSight(double x0, double y0, double x1, double y1) {
        int cx = cellX(x0);
        int cy = cellY(y0);
        int ex = cellX(x1);
        int ey = cellY(y1);
        double dx = x1 - x0;
        double dy = y1 - y0;
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? cellSize / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? cellSize / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tMaxX = dx > 0 ? ((cx + 1) * cellSize - x0) / dx
                : dx < 0 ? (x0 - cx * cellSize) / -dx : Double.POSITIVE_INFINITY;
        double tMaxY = dy > 0 ? ((cy + 1) * cellSize - y0) / dy
                : dy < 0 ? (y0 - cy * cellSize) / -dy : Double.POSITIVE_INFINITY;
        int guard = cols + rows + 2;
        while (guard-- > 0) {
            if (blockedCell(cx, cy)) {
                return false;
            }
            if (cx == ex && cy == ey) {
                return true;
            }
            if (tMaxX < tMaxY) {
                cx += stepX;
                tMaxX += tDeltaX;
            } else {
                cy += stepY;
                tMaxY += tDeltaY;
            }
        }
        return true;
    }
}
