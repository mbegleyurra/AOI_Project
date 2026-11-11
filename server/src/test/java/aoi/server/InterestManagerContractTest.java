package aoi.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reusable contract every InterestManager must satisfy. Subclass it and implement {@link #create()}.
 * Worlds use coordinates in 0..World.SIZE, like the real server.
 */
public abstract class InterestManagerContractTest {

    /** A fresh instance of the algorithm under test. Must be cheap and parameterised with sane defaults. */
    protected abstract InterestManager create();

    protected static List<Entity> randomEntities(int n, long seed) {
        Random rnd = new Random(seed);
        List<Entity> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new Entity(i, i % 2 == 0, rnd.nextDouble() * World.SIZE, rnd.nextDouble() * World.SIZE));
        }
        return list;
    }

    private static Set<Integer> ids(Collection<Entity> es) {
        Set<Integer> s = new HashSet<>();
        for (Entity e : es) {
            s.add(e.id);
        }
        return s;
    }

    @Test
    void metadataIsSane() {
        InterestManager im = create();
        assertNotNull(im.name());
        assertFalse(im.name().isBlank(), "name() must not be blank");
        assertTrue(im.radius() > 0 && Double.isFinite(im.radius()), "radius() must be positive and finite: " + im.radius());
    }

    @Test
    void viewerIsAlwaysVisibleToItself() {
        InterestManager im = create();
        for (long seed = 1; seed <= 20; seed++) {
            List<Entity> all = randomEntities(200, seed);
            for (int i = 0; i < all.size(); i += 7) {
                Entity viewer = all.get(i);
                List<Entity> out = im.visibleTo(viewer, all);
                assertTrue(ids(out).contains(viewer.id),
                        "viewer " + viewer.id + " at (" + viewer.x + "," + viewer.y + ") missing from its own result, seed " + seed);
            }
        }
    }

    @Test
    void viewerAtWorldCornersAndEdgesIsVisibleToItself() {
        InterestManager im = create();
        double s = World.SIZE;
        double[][] spots = {{0, 0}, {s, 0}, {0, s}, {s, s}, {s / 2, 0}, {0, s / 2}, {s, s / 2}, {s / 2, s}};
        for (double[] p : spots) {
            List<Entity> all = randomEntities(100, 99);
            Entity viewer = new Entity(1000, false, p[0], p[1]);
            all.add(viewer);
            assertTrue(ids(im.visibleTo(viewer, all)).contains(viewer.id), "viewer lost at (" + p[0] + "," + p[1] + ")");
        }
    }

    @Test
    void resultIsSubsetOfInput() {
        InterestManager im = create();
        for (long seed = 1; seed <= 10; seed++) {
            List<Entity> all = randomEntities(150, seed);
            Entity viewer = all.get((int) seed);
            List<Entity> out = im.visibleTo(viewer, all);
            for (Entity e : out) {
                assertTrue(all.stream().anyMatch(x -> x == e), "returned entity " + e.id + " is not an instance from the input");
            }
        }
    }

    @Test
    void noDuplicatesAndNoNulls() {
        InterestManager im = create();
        for (long seed = 1; seed <= 10; seed++) {
            List<Entity> all = randomEntities(300, seed);
            Entity viewer = all.get(0);
            List<Entity> out = im.visibleTo(viewer, all);
            assertNotNull(out);
            Set<Integer> seen = new HashSet<>();
            for (Entity e : out) {
                assertNotNull(e, "null element in result");
                assertTrue(seen.add(e.id), "duplicate entity id " + e.id + ", seed " + seed);
            }
        }
    }

    @Test
    void sameInputGivesSameOutput() {
        InterestManager im = create();
        List<Entity> all = randomEntities(250, 7);
        for (int i = 0; i < all.size(); i += 11) {
            Entity viewer = all.get(i);
            Set<Integer> first = ids(im.visibleTo(viewer, all));
            Set<Integer> second = ids(im.visibleTo(viewer, all));
            assertEquals(first, second, "same instance, same input, different output for viewer " + viewer.id);
            Set<Integer> fresh = ids(create().visibleTo(viewer, all));
            assertEquals(first, fresh, "fresh instance disagrees for viewer " + viewer.id);
        }
    }

    @Test
    void singleEntityWorldReturnsOnlyTheViewer() {
        InterestManager im = create();
        Entity viewer = new Entity(1, false, 500, 500);
        List<Entity> out = im.visibleTo(viewer, List.of(viewer));
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).id);
    }

    @Test
    void emptyWorldDoesNotThrowOrReturnNull() {
        InterestManager im = create();
        Entity viewer = new Entity(1, false, 500, 500);
        List<Entity> out = im.visibleTo(viewer, List.of());
        assertNotNull(out);
        for (Entity e : out) {
            assertNotNull(e);
        }
    }

    @Test
    void stackedEntitiesAtSamePointDoNotBreakContract() {
        InterestManager im = create();
        List<Entity> all = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            all.add(new Entity(i, true, 400, 400));
        }
        Entity viewer = all.get(0);
        List<Entity> out = im.visibleTo(viewer, all);
        assertEquals(out.size(), ids(out).size(), "duplicates when entities coincide");
        assertTrue(ids(out).contains(viewer.id));
    }

    @Test
    void doesNotMutateInput() {
        InterestManager im = create();
        List<Entity> all = randomEntities(100, 3);
        double[] xs = all.stream().mapToDouble(e -> e.x).toArray();
        double[] ys = all.stream().mapToDouble(e -> e.y).toArray();
        im.visibleTo(all.get(0), all);
        assertEquals(100, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(xs[i], all.get(i).x);
            assertEquals(ys[i], all.get(i).y);
        }
    }
}
