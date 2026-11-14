package aoi.server.bench;

import aoi.server.InterestManager;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/** Builds interest managers by simple class name via reflection, so missing classes are just skipped. */
public final class AlgorithmRegistry {
    public static final List<String> DEFAULT_NAMES = List.of(
            "AllInterest", "DistanceInterest", "SquareInterest", "HexagonalInterest", "TileDistanceInterest",
            "NeighborInterest", "RayVisibilityInterest", "TileVisibilityInterest", "PathDistanceInterest");

    private static final String[] PACKAGES = {"aoi.server.algo.", "aoi.server."};

    private AlgorithmRegistry() {
    }

    /** Returns the algorithms that exist and could be constructed; prints a notice for the rest. */
    public static List<InterestManager> build(List<String> names, double radius, long seed) {
        List<InterestManager> out = new ArrayList<>();
        for (String name : names) {
            InterestManager m = create(name.trim(), radius, seed);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    private static InterestManager create(String name, double radius, long seed) {
        Class<?> cls = null;
        for (String pkg : PACKAGES) {
            try {
                cls = Class.forName(name.contains(".") ? name : pkg + name);
                break;
            } catch (ClassNotFoundException ignored) {
                if (name.contains(".")) {
                    break;
                }
            }
        }
        if (cls == null) {
            System.out.println("[registry] skipping " + name + ": class not found");
            return null;
        }
        try {
            String simple = cls.getSimpleName();
            // Grid classes take a cell size, not a radius: size them so the effective radius ~= `radius`
            // (1 ring/tile around the viewer's cell). Tile index age 0 = rebuild every call (exact; the
            // default 10 ms wall-clock cache would go stale in a headless run that ticks faster than 10 ms).
            double tile = radius / 1.5;
            double hexCell = radius / (3 * Math.sqrt(3.0) / 2.0);
            Object o;
            if (simple.contains("Visibility") || simple.startsWith("Path")) {
                Class<?> oc = Class.forName("aoi.server.Obstacles");
                Object obs = oc.getMethod("generate", long.class).invoke(null, seed);
                if (simple.startsWith("Path")) {
                    double cell = (Double) oc.getMethod("cellSize").invoke(obs);
                    int steps = (int) Math.ceil(radius / cell);
                    o = cls.getConstructor(oc, int.class).newInstance(obs, steps);
                } else {
                    o = cls.getConstructor(oc, double.class).newInstance(obs, radius);
                }
            } else if (simple.contains("Hex")) {
                o = cls.getConstructor(double.class, int.class).newInstance(hexCell, 1);
            } else if (simple.startsWith("Tile") || simple.startsWith("Neighbor")) {
                try {
                    o = cls.getConstructor(double.class, int.class, long.class).newInstance(tile, 1, 0L);
                } catch (NoSuchMethodException e1) {
                    try {
                        o = cls.getConstructor(double.class, long.class).newInstance(tile, 0L);
                    } catch (NoSuchMethodException e2) {
                        try {
                            o = cls.getConstructor(double.class, int.class).newInstance(tile, 1);
                        } catch (NoSuchMethodException e3) {
                            o = cls.getConstructor(double.class).newInstance(tile);
                        }
                    }
                }
            } else {
                try {
                    o = cls.getConstructor(double.class).newInstance(radius);
                } catch (NoSuchMethodException e) {
                    o = cls.getConstructor().newInstance();
                }
            }
            return (InterestManager) o;
        } catch (Exception | LinkageError e) {
            System.out.println("[registry] skipping " + name + ": cannot construct (" + e + ")");
            return null;
        }
    }
}
