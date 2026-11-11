package aoi.server.bench;

import aoi.server.DistanceInterest;
import aoi.server.Entity;
import aoi.server.InterestManager;
import aoi.server.World;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Headless AOI benchmark. No sockets: a seeded World is stepped for N ticks and every
 * algorithm is asked visibleTo() for each simulated player each tick.
 *
 * Usage: BenchMain [bots] [players] [ticks] [radius] [seed] [outputCsv] [perTickCsv] [algos(comma list)]
 */
public final class BenchMain {
    private static final double DT = 1.0 / 20.0; // GameServer.TICK_HZ = 20
    private static final int WARMUP_TICKS = 100;

    private static final class Stats {
        long calls, entities, bytes, nanos, maxNanos, tp, returned, truth;
    }

    public static void main(String[] args) throws IOException {
        int bots = argInt(args, 0, 1000);
        int players = argInt(args, 1, 20);
        int ticks = argInt(args, 2, 600);
        double radius = args.length > 3 ? Double.parseDouble(args[3]) : 100.0;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 42L;
        String out = args.length > 5 ? args[5] : "bench-results.csv";
        String perTick = args.length > 6 && !args[6].isBlank() && !args[6].equals("-") ? args[6] : null;
        List<String> names = args.length > 7 ? Arrays.asList(args[7].split(",")) : AlgorithmRegistry.DEFAULT_NAMES;

        List<InterestManager> algos = AlgorithmRegistry.build(names, radius, seed);
        if (algos.isEmpty()) {
            System.err.println("No algorithms available.");
            return;
        }
        System.out.printf("bench: bots=%d players=%d ticks=%d radius=%.1f seed=%d algos=%d%n",
                bots, players, ticks, radius, seed, algos.size());

        List<String> rows = new ArrayList<>();
        PrintWriter tickOut = null;
        if (perTick != null) {
            tickOut = open(perTick);
            tickOut.println("algorithm,tick,avg_entities_per_client,messages,bytes,visible_ns,precision,recall,missed");
        }

        DistanceInterest oracle = new DistanceInterest(radius);
        for (InterestManager algo : algos) {
            warmup(algo, bots, players, radius, seed);
            Stats s = run(algo, oracle, bots, players, ticks, seed, tickOut);
            rows.add(row(algo, bots, players, ticks, radius, seed, s));
        }
        if (tickOut != null) {
            tickOut.close();
        }

        try (PrintWriter w = open(out)) {
            w.println("algorithm,bots,players,ticks,radius,seed,avg_entities_per_client,messages,total_bytes,"
                    + "avg_bytes_per_client_tick,total_visible_ns,avg_visible_ns,max_visible_ns,precision,recall,missed,extra");
            rows.forEach(w::println);
        }
        printTable(rows);
        System.out.println("wrote " + Path.of(out).toAbsolutePath()
                + (perTick != null ? " and " + Path.of(perTick).toAbsolutePath() : ""));
    }

    private static void warmup(InterestManager algo, int bots, int players, double radius, long seed) {
        World w = new World(bots, seed);
        List<Entity> ps = spawn(w, players);
        Random path = new Random(seed ^ 0x9E3779B9L);
        for (int t = 0; t < WARMUP_TICKS; t++) {
            drive(w, ps, path, t);
            w.step(DT);
            for (Entity p : ps) {
                algo.visibleTo(p, w.all());
            }
        }
    }

    private static Stats run(InterestManager algo, InterestManager oracle, int bots, int players,
                             int ticks, long seed, PrintWriter tickOut) {
        World w = new World(bots, seed);
        List<Entity> ps = spawn(w, players);
        Random path = new Random(seed ^ 0x9E3779B9L);
        Stats s = new Stats();
        for (int t = 0; t < ticks; t++) {
            drive(w, ps, path, t);
            w.step(DT);
            Collection<Entity> all = w.all();
            long tEnt = 0, tBytes = 0, tNs = 0, tTp = 0, tRet = 0, tTruth = 0;
            for (Entity p : ps) {
                long t0 = System.nanoTime();
                List<Entity> got = algo.visibleTo(p, all);
                long ns = System.nanoTime() - t0;

                Set<Integer> truth = ids(oracle.visibleTo(p, all));
                Set<Integer> gotIds = ids(got);
                long tp = 0;
                for (int id : gotIds) {
                    if (truth.contains(id)) {
                        tp++;
                    }
                }
                int n = got.size();
                tEnt += n;
                tBytes += 9L + 12L * n;
                tNs += ns;
                s.maxNanos = Math.max(s.maxNanos, ns);
                tTp += tp;
                tRet += gotIds.size();
                tTruth += truth.size();
            }
            s.calls += ps.size();
            s.entities += tEnt;
            s.bytes += tBytes;
            s.nanos += tNs;
            s.tp += tTp;
            s.returned += tRet;
            s.truth += tTruth;
            if (tickOut != null) {
                tickOut.printf(Locale.ROOT, "%s,%d,%.3f,%d,%d,%d,%.5f,%.5f,%d%n", algo.name().replace(',', ';'), t,
                        ps.isEmpty() ? 0 : (double) tEnt / ps.size(), ps.size(), tBytes, tNs,
                        tRet == 0 ? 1.0 : (double) tTp / tRet, tTruth == 0 ? 1.0 : (double) tTp / tTruth,
                        tTruth - tTp);
            }
        }
        return s;
    }

    private static List<Entity> spawn(World w, int players) {
        List<Entity> ps = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            ps.add(w.spawnPlayer());
        }
        return ps;
    }

    /** Scripted-random player motion: new random heading every 40 ticks (staggered), occasional pauses. */
    private static void drive(World w, List<Entity> ps, Random path, int tick) {
        for (int i = 0; i < ps.size(); i++) {
            if ((tick + i * 7) % 40 == 0) {
                double a = path.nextDouble() * Math.PI * 2;
                boolean pause = path.nextDouble() < 0.1;
                w.setPlayerVelocity(ps.get(i), pause ? 0 : Math.cos(a), pause ? 0 : Math.sin(a));
            }
        }
    }

    private static Set<Integer> ids(List<Entity> l) {
        Set<Integer> s = new HashSet<>(l.size() * 2);
        for (Entity e : l) {
            s.add(e.id);
        }
        return s;
    }

    private static String row(InterestManager a, int bots, int players, int ticks, double radius, long seed, Stats s) {
        double avgEnt = s.calls == 0 ? 0 : (double) s.entities / s.calls;
        double avgBytes = s.calls == 0 ? 0 : (double) s.bytes / s.calls;
        double avgNs = s.calls == 0 ? 0 : (double) s.nanos / s.calls;
        double prec = s.returned == 0 ? 1.0 : (double) s.tp / s.returned;
        double rec = s.truth == 0 ? 1.0 : (double) s.tp / s.truth;
        return String.format(Locale.ROOT, "%s,%d,%d,%d,%.1f,%d,%.3f,%d,%d,%.2f,%d,%.1f,%d,%.5f,%.5f,%d,%d",
                a.name().replace(',', ';'), bots, players, ticks, radius, seed, avgEnt, s.calls, s.bytes, avgBytes,
                s.nanos, avgNs, s.maxNanos, prec, rec, s.truth - s.tp, s.returned - s.tp);
    }

    private static void printTable(List<String> rows) {
        System.out.printf("%-28s %10s %10s %12s %9s %9s %9s %9s%n",
                "algorithm", "ent/client", "B/client", "avg ns/call", "precision", "recall", "missed", "extra");
        for (String r : rows) {
            String[] c = r.split(",");
            System.out.printf("%-28s %10s %10s %12s %9s %9s %9s %9s%n",
                    c[0], c[6], c[9], c[11], c[13], c[14], c[15], c[16]);
        }
    }

    private static PrintWriter open(String path) throws IOException {
        Path p = Path.of(path).toAbsolutePath();
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        return new PrintWriter(Files.newBufferedWriter(p));
    }

    private static int argInt(String[] a, int i, int def) {
        return a.length > i ? Integer.parseInt(a[i]) : def;
    }
}
