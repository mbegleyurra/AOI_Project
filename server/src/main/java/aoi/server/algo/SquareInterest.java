package aoi.server.algo;

import aoi.server.Entity;
import aoi.server.InterestManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Axis-aligned square of half-width r centred on the viewer. */
public final class SquareInterest implements InterestManager {
    private final double halfWidth;

    public SquareInterest(double halfWidth) {
        this.halfWidth = halfWidth;
    }

    @Override
    public String name() {
        return "square(r=" + halfWidth + ")";
    }

    @Override
    public double radius() {
        return halfWidth;
    }

    @Override
    public List<Entity> visibleTo(Entity viewer, Collection<Entity> all) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            if (Math.abs(e.x - viewer.x) <= halfWidth && Math.abs(e.y - viewer.y) <= halfWidth) {
                out.add(e);
            }
        }
        return out;
    }
}
