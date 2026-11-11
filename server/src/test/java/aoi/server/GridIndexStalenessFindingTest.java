package aoi.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.server.algo.NeighborInterest;
import aoi.server.algo.TileDistanceInterest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Documents a finding: GridIndex skips rebuilding when the entity COUNT is unchanged and the index is
 * younger than indexMaxAgeMs (default 10 ms). A player leaving and another joining inside that window
 * leaves the index holding the removed entity and missing the new one, including the viewer itself.
 * These tests pass today by asserting the defect exists; if they start failing, the defect was fixed.
 */
class GridIndexStalenessFindingTest {

    private static List<Entity> world(int firstId) {
        List<Entity> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(new Entity(firstId + i, true, 100 + i, 100));
        }
        return all;
    }

    @Test
    void tileDistanceServesRemovedEntityAndHidesNewViewerWithinIndexWindow() {
        InterestManager im = new TileDistanceInterest(50, 2);
        List<Entity> before = world(1);
        Entity oldFirst = before.get(0);
        im.visibleTo(oldFirst, before);

        List<Entity> after = world(1);
        after.remove(0);
        Entity newcomer = new Entity(99, false, 105, 100);
        after.add(newcomer);

        List<Entity> out = im.visibleTo(newcomer, after);
        assertFalse(out.contains(newcomer), "stale index: newly joined viewer absent from its own result");
        assertTrue(out.contains(oldFirst), "stale index: removed entity still returned");
    }

    @Test
    void neighborShowsSameStaleness() {
        InterestManager im = new NeighborInterest(50);
        List<Entity> before = world(1);
        im.visibleTo(before.get(0), before);
        List<Entity> after = world(1);
        after.remove(0);
        Entity newcomer = new Entity(99, false, 105, 100);
        after.add(newcomer);
        assertFalse(im.visibleTo(newcomer, after).contains(newcomer));
    }
}
