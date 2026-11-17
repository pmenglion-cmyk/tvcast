package app.tvcast.dlna;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds DLNA MediaRenderers (Samsung TVs and others) on the local network using SSDP. */
public final class Discovery {

    public interface Listener {
        void onFound(Renderer r);
        void onLog(String msg);
    }

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
    };

    /** Well-known description URLs, tried when the user types a TV's IP address. */
    private static final String[] KNOWN_DESCRIPTIONS = {
            "http://%s:9197/dmr",               // Samsung Tizen TVs (2016+)
            "http://%s:7676/smp_15_",           // older Samsung TVs
            "http://%s:7676/smp_14_",
            "http://%s:7676/smp_16_",
            "http://%s:1400/xml/device_description.xml", // Sonos
            "http://%s:49152/description.xml",
    };

    private Discovery() { }

    /** Blocks for about {@code timeoutMs}, calling the listener for each renderer found. */
    public static void search(int timeoutMs, Listener l) {
        final Set<String> seenLocations = Collections.synchronizedSet(new HashSet<String>());
        final Set<String> seenIds = Collections.synchronizedSet(new HashSet<String>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Thread> threads = new ArrayList<>();
        List<InetAddress> locals = localIpv4Addresses();
        if (locals.isEmpty()) {
            l.onLog("Not connected to Wi-Fi.");
        }
        for (final InetAddress local : locals) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    searchOn(local, null, timeoutMs, seenLocations, seenIds, pool, l);
                }
            }, "ssdp-" + local.getHostAddress());
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(timeoutMs + 2000L); } catch (InterruptedException ignored) { }
        }
        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    /**
     * Looks for a renderer at a specific IP address: unicast SSDP first, then well-known
     * description URLs. Returns null if nothing is found.
     */
    public static Renderer probe(String ip, Listener l) {
        final Renderer[] found = new Renderer[1];
        final Set<String> seenIds = Collections.synchronizedSet(new HashSet<String>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Listener capture = new Listener() {
            @Override public void onFound(Renderer r) {
                synchronized (found) { if (found[0] == null) found[0] = r; }
                l.onFound(r);
            }
            @Override public void onLog(String msg) { l.onLog(msg); }
        };
        try {
            InetAddress target = InetAddress.getByName(ip);
            searchOn(null, target, 2500, Collections.synchronizedSet(new HashSet<String>()), seenIds, pool, capture);
        } catch (IOException e) {
            l.onLog("Bad address: " + ip);
            return null;
        }
        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (found[0] != null) return found[0];
        for (String pattern : KNOWN_DESCRIPTIONS) {
            String url = String.format(Locale.US, pattern, ip);
            try {
                Renderer r = Renderer.fromDescription(url);
                if (r != null) {
                    l.onFound(r);
                    return r;
                }
            } catch (IOException ignored) {
                // try the next one
            }
        }
        return null;
    }

    private static void searchOn(InetAddress local, InetAddress unicastTarget, int timeoutMs,
                                 final Set<String> seenLocations, final Set<String> seenIds,
                                 ExecutorService pool, final Listener l) {
        MulticastSocket sock = null;
        try {
            sock = new MulticastSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(local, 0));
            if (local != null) {
                NetworkInterface ni = NetworkInterface.getByInetAddress(local);
                if (ni != null) sock.setNetworkInterface(ni);
            }
            sock.setTimeToLive(4);
            InetAddress dest = unicastTarget != null ? unicastTarget : InetAddress.getByName(SSDP_ADDR);
            // UDP is lossy; send each query a few times.
            for (int round = 0; round < 3; round++) {
                for (String st : SEARCH_TARGETS) {
                    byte[] msg = ("M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 2\r\n"
                            + "ST: " + st + "\r\n"
                            + "USER-AGENT: Android/1.0 UPnP/1.1 TVCast/1.0\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(msg, msg.length, dest, SSDP_PORT));
                }
                if (round == 0) sleep(150);
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                sock.setSoTimeout((int) Math.max(1, left));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                final String loc = header(resp, "location");
                if (loc == null || !seenLocations.add(loc)) continue;
                pool.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            Renderer r = Renderer.fromDescription(loc);
                            if (r != null && seenIds.add(r.id())) l.onFound(r);
                        } catch (IOException e) {
                            l.onLog("Could not read " + loc + ": " + e.getMessage());
                        }
                    }
                });
            }
        } catch (IOException e) {
            l.onLog("Search failed on " + (local == null ? "network" : local.getHostAddress()) + ": " + e.getMessage());
        } finally {
            if (sock != null) sock.close();
        }
    }

    static String header(String resp, String name) {
        for (String line : resp.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    /** IPv4 addresses of the phone's active LAN interfaces (Wi-Fi, hotspot, ethernet). */
    public static List<InetAddress> localIpv4Addresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                String n = ni.getName().toLowerCase(Locale.US);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") || n.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) out.add(a);
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
