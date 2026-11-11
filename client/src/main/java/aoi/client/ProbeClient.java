package aoi.client;

import aoi.common.Protocol;

import java.util.concurrent.atomic.AtomicInteger;

/** Headless smoke test: connect, read a few snapshots, print them, exit. Usage: ProbeClient [host] [port] */
public final class ProbeClient {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
        try (Connection c = new Connection(host, port)) {
            System.out.println("welcome: id=" + c.clientId + " world=" + c.worldW + "x" + c.worldH + " radius=" + c.radius);
            AtomicInteger seen = new AtomicInteger();
            Thread t = new Thread(() -> c.readLoop(s -> {
                System.out.println("snapshot tick=" + s.tick() + " visible=" + s.ids().length);
                seen.incrementAndGet();
            }));
            t.setDaemon(true);
            t.start();
            long end = System.currentTimeMillis() + 3000;
            while (seen.get() < 5 && System.currentTimeMillis() < end) {
                Thread.sleep(50);
            }
            System.out.println(seen.get() >= 5 ? "PROBE OK" : "PROBE FAILED");
            System.exit(seen.get() >= 5 ? 0 : 1);
        }
    }
}
