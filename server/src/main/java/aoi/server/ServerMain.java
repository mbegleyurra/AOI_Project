package aoi.server;

import aoi.common.Protocol;
import aoi.server.algo.AllInterest;
import aoi.server.algo.RayVisibilityInterest;

import java.util.ArrayList;
import java.util.List;

/**
 * Usage: ServerMain [bots=300] [radius=150] [port=7777] [seed=42] [spectatorPort=port+1] [--algo=distance|ray|all]
 *                   [--obstacles=24]
 *
 * <p>--algo=distance (default) is plain radius interest. --algo=ray is ray visibility interest: within radius AND
 * line of sight. Both run on the same authoritative obstacle layout (Obstacles.generateRects, seeded with the
 * same seed) with collision; --obstacles=N sets the obstacle count, 0 means open terrain (and then ray
 * visibility sees everything in radius).
 * The flag may appear anywhere; the remaining arguments stay positional.
 */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        String algo = "distance";
        int obstacleCount = 24;
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--algo=")) {
                algo = a.substring("--algo=".length()).trim().toLowerCase();
            } else if (a.startsWith("--obstacles=")) {
                obstacleCount = Integer.parseInt(a.substring("--obstacles=".length()).trim());
            } else {
                pos.add(a);
            }
        }
        int bots = pos.size() > 0 ? Integer.parseInt(pos.get(0)) : 300;
        double radius = pos.size() > 1 ? Double.parseDouble(pos.get(1)) : 150;
        int port = pos.size() > 2 ? Integer.parseInt(pos.get(2)) : Protocol.DEFAULT_PORT;
        long seed = pos.size() > 3 ? Long.parseLong(pos.get(3)) : 42;
        int spectatorPort = pos.size() > 4 ? Integer.parseInt(pos.get(4)) : port + 1;

        World world = new World(bots, seed);
        Obstacles obstacles = Obstacles.generateRects(seed, obstacleCount, 10.0, 4, 12, 3);
        if (obstacleCount > 0) {
            world.setObstacles(obstacles);
        }
        InterestManager interest;
        switch (algo) {
            case "distance" -> interest = new DistanceInterest(radius);
            case "ray" -> interest = new RayVisibilityInterest(obstacles, radius);
            case "all" -> interest = new AllInterest(radius);
            default -> throw new IllegalArgumentException("unknown --algo=" + algo + " (use distance, ray or all)");
        }
        new GameServer(world, interest, port, spectatorPort).start();
    }
}
