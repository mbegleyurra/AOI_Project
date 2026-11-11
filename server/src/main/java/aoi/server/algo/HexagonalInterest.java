package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Pointy-top hex grid. cellRadius is centre-to-corner. An entity is visible when its hex cell is
 * within `rings` hex steps of the viewer's cell (rings=0: own cell, 1: own cell + 6 neighbours).
 */
public final class HexagonalInterest implements InterestManager {
    private static final double SQRT3 = Math.sqrt(3.0);
    private final double cellRadius;
    private final int rings;

    public HexagonalInterest(double cellRadius, int rings) {
        if (cellRadius <= 0 || rings < 0) {
            throw new IllegalArgumentException("cellRadius must be > 0 and rings >= 0");
        }
        this.cellRadius = cellRadius;
        this.rings = rings;
    }

    @Override
    public String name() {
        return "hex(cell=" + cellRadius + ",rings=" + rings + ")";
    }

    /** Approximate drawn radius: centre-to-edge distance of the outer ring. */
    @Override
    public double radius() {
        return (rings * 2 + 1) * SQRT3 / 2.0 * cellRadius;
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        int[] v = cellOf(viewer.x, viewer.y);
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            int[] c = cellOf(e.x, e.y);
            int dq = c[0] - v[0];
            int dr = c[1] - v[1];
            if ((Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2 <= rings) {
                out.add(e);
            }
        }
        return out;
    }

    /** Axial (q, r) of the hex containing the point, via cube rounding. */
    private int[] cellOf(double x, double y) {
        double q = (SQRT3 / 3.0 * x - y / 3.0) / cellRadius;
        double r = (2.0 / 3.0 * y) / cellRadius;
        double s = -q - r;
        double rq = Math.rint(q);
        double rr = Math.rint(r);
        double rs = Math.rint(s);
        double dq = Math.abs(rq - q);
        double dr = Math.abs(rr - r);
        double ds = Math.abs(rs - s);
        if (dq > dr && dq > ds) {
            rq = -rr - rs;
        } else if (dr > ds) {
            rr = -rq - rs;
        }
        return new int[] {(int) rq, (int) rr};
    }
}
