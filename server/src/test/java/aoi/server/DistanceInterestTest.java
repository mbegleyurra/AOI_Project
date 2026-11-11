package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DistanceInterestTest extends InterestManagerContractTest {
    private static final double R = 100.0;

    @Override
    protected InterestManager create() {
        return new DistanceInterest(R);
    }

    private static Set<Integer> ids(List<Entity> es) {
        return es.stream().map(e -> e.id).collect(Collectors.toSet());
    }

    @Test
    void reportsConfiguredRadius() {
        assertEquals(R, create().radius());
    }

    @Test
    void includesEverythingWithinRadiusAndExcludesEverythingBeyond() {
        InterestManager im = create();
        for (long seed = 1; seed <= 10; seed++) {
            List<Entity> all = randomEntities(500, seed);
            Entity viewer = all.get(0);
            Set<Integer> got = ids(im.visibleTo(viewer, all));
            for (Entity e : all) {
                double d = Math.hypot(e.x - viewer.x, e.y - viewer.y);
                if (d < R - 1e-9) {
                    assertTrue(got.contains(e.id), "entity " + e.id + " at distance " + d + " should be visible, seed " + seed);
                } else if (d > R + 1e-9) {
                    assertFalse(got.contains(e.id), "entity " + e.id + " at distance " + d + " should be hidden, seed " + seed);
                }
            }
        }
    }

    @Test
    void boundaryIsInclusive() {
        Entity viewer = new Entity(1, false, 500, 500);
        Entity onEdge = new Entity(2, true, 500 + R, 500);
        Entity justOutside = new Entity(3, true, 500 + R + 0.001, 500);
        Set<Integer> got = ids(create().visibleTo(viewer, List.of(viewer, onEdge, justOutside)));
        assertTrue(got.contains(2), "entity exactly at radius should be visible");
        assertFalse(got.contains(3), "entity just beyond radius should be hidden");
    }

    @Test
    void viewerIncludedExactlyOnceWhenPresent() {
        Entity viewer = new Entity(1, false, 10, 10);
        List<Entity> out = create().visibleTo(viewer, List.of(viewer));
        assertEquals(1, out.stream().filter(e -> e == viewer).count());
    }

    @Test
    void viewerNotInventedWhenAbsentFromInput() {
        Entity viewer = new Entity(1, false, 10, 10);
        Entity other = new Entity(2, true, 20, 10);
        List<Entity> out = create().visibleTo(viewer, List.of(other));
        assertEquals(Set.of(2), ids(out), "result must only contain input entities");
    }

    @Test
    void diagonalDistanceIsEuclideanNotChebyshev() {
        Entity viewer = new Entity(1, false, 500, 500);
        // (80,80) is inside the square of side 2R but at distance ~113 > 100.
        Entity corner = new Entity(2, true, 580, 580);
        assertFalse(ids(create().visibleTo(viewer, List.of(viewer, corner))).contains(2));
    }

    @Test
    void emptyAndSingleWorlds() {
        Entity viewer = new Entity(1, false, 0, 0);
        assertTrue(create().visibleTo(viewer, List.of()).isEmpty());
        assertEquals(List.of(viewer), create().visibleTo(viewer, List.of(viewer)));
    }

    @Test
    void deterministicForSameSeedWorld() {
        List<List<Integer>> runs = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            World w = new World(300, 12345L);
            Entity viewer = w.spawnPlayer();
            for (int i = 0; i < 20; i++) {
                w.step(0.05);
            }
            InterestManager im = create();
            List<Integer> result = im.visibleTo(viewer, w.all()).stream().map(e -> e.id).sorted().toList();
            runs.add(result);
        }
        assertEquals(runs.get(0), runs.get(1));
        assertEquals(runs.get(1), runs.get(2));
    }
}
