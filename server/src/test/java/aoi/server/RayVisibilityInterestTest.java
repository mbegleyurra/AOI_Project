package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aoi.server.algo.RayVisibilityInterest;
import java.util.List;
import org.junit.jupiter.api.Test;

class RayVisibilityInterestTest extends InterestManagerContractTest {
    @Override
    protected InterestManager create() {
        return new RayVisibilityInterest(Obstacles.generate(1L), 150);
    }

    /** One solid 3x3-cell block (10-unit cells) well inside the map; x0,y0 are its lowest cell indices. */
    private record Block(Obstacles map, int x0, int y0) {
        /** World coordinate of the centre of cell index c. */
        static double mid(int c) {
            return (c + 0.5) * 10.0;
        }
    }

    private static Block block() {
        for (long seed = 1; seed < 1000; seed++) {
            Obstacles o = Obstacles.generate(seed, 1, 10.0, 3, 3);
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int count = 0;
            for (int y = 0; y < o.rows(); y++) {
                for (int x = 0; x < o.cols(); x++) {
                    if (o.blockedCell(x, y)) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        count++;
                    }
                }
            }
            if (count == 9 && minX >= 4 && minX <= 90 && minY >= 0 && minY <= 88) {
                return new Block(o, minX, minY);
            }
        }
        throw new AssertionError("no seed produced a usable block");
    }

    @Test
    void entityBehindObstacleIsExcluded() {
        Block b = block();
        double y = Block.mid(b.y0() + 1);
        Entity viewer = new Entity(1, false, Block.mid(b.x0() - 3), y);
        Entity behind = new Entity(2, true, Block.mid(b.x0() + 5), y);
        assertTrue(b.map().lineOfSight(viewer.x, viewer.y, viewer.x, viewer.y + 1), "fixture: viewer cell is open");
        assertFalse(b.map().isBlocked(behind.x, behind.y), "fixture: target cell is open");
        List<Entity> out = new RayVisibilityInterest(b.map(), 150).visibleTo(viewer, List.of(viewer, behind));
        assertFalse(out.contains(behind), "wall between viewer and entity must block it");
        assertTrue(out.contains(viewer));
    }

    @Test
    void entityInClearViewIsIncluded() {
        Block b = block();
        Entity viewer = new Entity(1, false, Block.mid(b.x0() - 3), Block.mid(b.y0() + 1));
        Entity clear = new Entity(2, true, viewer.x, viewer.y + 60);
        List<Entity> out = new RayVisibilityInterest(b.map(), 150).visibleTo(viewer, List.of(viewer, clear));
        assertTrue(out.contains(clear));
    }

    @Test
    void clearEntityBeyondRadiusIsExcluded() {
        Entity viewer = new Entity(1, false, 100, 100);
        Entity far = new Entity(2, true, 100, 300);
        List<Entity> out = new RayVisibilityInterest(new Obstacles(10.0), 150).visibleTo(viewer, List.of(viewer, far));
        assertEquals(List.of(viewer), out);
    }

    @Test
    void viewerInsideWallStillSeesItself() {
        Block b = block();
        Entity viewer = new Entity(1, false, Block.mid(b.x0() + 1), Block.mid(b.y0() + 1));
        assertTrue(b.map().isBlocked(viewer.x, viewer.y), "fixture: viewer stands in a wall cell");
        List<Entity> out = new RayVisibilityInterest(b.map(), 150).visibleTo(viewer, List.of(viewer));
        assertEquals(List.of(viewer), out);
    }

    @Test
    void threeHundredViewersFitInOneTick() {
        Obstacles o = Obstacles.generate(42L);
        World w = new World(300, 42L);
        w.setObstacles(o);
        RayVisibilityInterest im = new RayVisibilityInterest(o, 150);
        List<Entity> all = List.copyOf(w.all());
        for (int i = 0; i < 3; i++) {
            for (Entity v : all) {
                im.visibleTo(v, all);
            }
        }
        long t0 = System.nanoTime();
        for (Entity v : all) {
            im.visibleTo(v, all);
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.println("[ray] 300 viewers x 300 entities, one tick: " + ms + " ms (budget " + 1000 / 20 + " ms)");
        assertTrue(ms < 1000.0 / 20, "one full tick of ray visibility took " + ms + " ms, over the 50 ms tick budget");
    }
}
