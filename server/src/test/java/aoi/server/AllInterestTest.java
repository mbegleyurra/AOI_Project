package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.server.algo.AllInterest;
import java.util.List;
import org.junit.jupiter.api.Test;

class AllInterestTest extends InterestManagerContractTest {

    @Override
    protected InterestManager create() {
        return new AllInterest();
    }

    @Test
    void everyEntityIsVisibleRegardlessOfDistance() {
        InterestManager im = create();
        for (long seed = 1; seed <= 5; seed++) {
            List<Entity> all = randomEntities(300, seed);
            Entity viewer = all.get(0);
            List<Entity> out = im.visibleTo(viewer, all);
            assertEquals(all.size(), out.size(), "all entities must be visible, seed " + seed);
            assertTrue(out.containsAll(all));
        }
    }

    @Test
    void farCornersAreVisible() {
        Entity viewer = new Entity(1, false, 0, 0);
        Entity far = new Entity(2, true, World.SIZE, World.SIZE);
        assertTrue(create().visibleTo(viewer, List.of(viewer, far)).contains(far));
    }

    @Test
    void radiusCoversTheWholeWorldAndNameIsAll() {
        InterestManager im = create();
        assertTrue(im.radius() > Math.hypot(World.SIZE, World.SIZE));
        assertEquals("all", im.name());
    }
}
