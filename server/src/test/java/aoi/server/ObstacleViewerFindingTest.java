package aoi.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.server.algo.RayVisibilityInterest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression for the RayVisibilityInterest contract failure: a viewer standing in a wall cell must still see itself. */
class ObstacleViewerFindingTest {

    @Test
    void viewerInsideWallCellStillSeesItself() {
        Obstacles o = Obstacles.generate(1L);
        double x = 815.7702414514118;
        double y = 155.57863513492433;
        assertTrue(o.isBlocked(x, y), "fixture assumption: this point is inside a wall cell");
        Entity viewer = new Entity(120, false, x, y);
        List<Entity> out = new RayVisibilityInterest(o, 150).visibleTo(viewer, List.of(viewer));
        assertTrue(out.contains(viewer), "viewer must always be in its own result");
    }
}
