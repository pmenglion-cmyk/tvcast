package app.tvcast.dlna;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Tiny HTTP/1.1 server that streams one {@link MediaSource} to a DLNA renderer.
 * Supports HEAD, GET and byte ranges (needed for seeking) and sends the DLNA
 * headers Samsung TVs expect.
 */
public final class MediaServer {

    /** Flags: streaming transfer, byte-range seek supported, not transcoded. */
    public static final String DLNA_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

    private static final int[] PREFERRED_PORTS = {8642, 8643, 8644, 0};

    private static MediaServer instance;

    private final SecureRandom random = new SecureRandom();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile MediaSource source;
    private volatile String path;
    private volatile Listener listener;

    public interface Listener {
        void onRequest(String clientIp, String range);
        void onError(String message);
    }

    public static synchronized MediaServer get() {
        if (instance == null) instance = new MediaServer();
        return instance;
    }

    public void setListener(Listener l) { listener = l; }

    public synchronized boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    public synchronized int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public synchronized void start() throws IOException {
        if (isRunning()) return;
        IOException last = null;
        for (int p : PREFERRED_PORTS) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(p), 16);
                serverSocket = ss;
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        final ServerSocket ss = serverSocket;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(ss); }
        }, "tvcast-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        source = null;
        path = null;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { }
            serverSocket = null;
        }
    }

    /**
     * Makes {@code src} available and returns its URL as seen from {@code localIp}.
     * Each call gets a fresh random path so the TV never plays a cached old file.
     */
    public synchronized String publish(MediaSource src, InetAddress localIp) throws IOException {
        start();
        byte[] b = new byte[9];
        random.nextBytes(b);
        StringBuilder token = new StringBuilder();
        for (byte x : b) token.append(String.format(Locale.US, "%02x", x));
        String ext = extensionFor(src.mimeType());
        source = src;
        path = "/media/" + token + "/video" + ext;
        return "http://" + localIp.getHostAddress() + ":" + port() + path;
    }

    static String extensionFor(String mime) {
        if (mime == null) return ".mp4";
        switch (mime) {
            case "video/x-matroska": return ".mkv";
            case "video/webm": return ".webm";
            case "video/quicktime": return ".mov";
            case "video/x-msvideo": return ".avi";
            case "video/mp2t": return ".ts";
            case "audio/mpeg": return ".mp3";
            default: return ".mp4";
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                final Socket s = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(s); }
                }, "tvcast-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!ss.isClosed()) report("Server error: " + e.getMessage());
            }
        }
    }

    private void report(String msg) {
        Listener l = listener;
        if (l != null) l.onError(msg);
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            // Serve requests on this connection until the client closes it.
            while (true) {
                Request req = Request.read(in);
                if (req == null) break;
                boolean keepAlive = serve(req, out, s);
                out.flush();
                if (!keepAlive) break;
            }
        } catch (SocketTimeoutException | SocketException ignored) {
            // TV closed the connection or went idle: normal when it seeks or buffers enough.
        } catch (IOException e) {
            report("Stream error: " + e.getMessage());
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** @return whether the connection may be reused. */
    private boolean serve(Request req, OutputStream out, Socket s) throws IOException {
        MediaSource src = source;
        String p = path;
        String reqPath = req.path;
        int q = reqPath.indexOf('?');
        if (q >= 0) reqPath = reqPath.substring(0, q);

        boolean isHead = "HEAD".equals(req.method);
        if (!isHead && !"GET".equals(req.method)) {
            simple(out, 405, "Method Not Allowed");
            return false;
        }
        if (src == null || p == null || !p.equals(reqPath)) {
            simple(out, 404, "Not Found");
            return false;
        }

        long total = src.length();
        long start = 0, end = total - 1;
        boolean partial = false;
        String range = req.header("range");
        if (range != null) {
            long[] r = parseRange(range, total);
            if (r == null) {
                String h = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + total + "\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(h.getBytes(StandardCharsets.US_ASCII));
                return false;
            }
            start = r[0];
            end = r[1];
            partial = true;
        }
        long len = end - start + 1;

        Listener l = listener;
        if (l != null) l.onRequest(s.getInetAddress().getHostAddress(), range);

        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(src.mimeType()).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        if (partial) {
            h.append("Content-Range: bytes ").append(start).append('-').append(end)
             .append('/').append(total).append("\r\n");
        }
        h.append("Accept-Ranges: bytes\r\n");
        h.append("transferMode.dlna.org: Streaming\r\n");
        h.append("contentFeatures.dlna.org: ").append(DLNA_FEATURES).append("\r\n");
        h.append("Cache-Control: no-cache\r\n");
        h.append("Server: Android/1.0 UPnP/1.0 TVCast/1.0\r\n");
        h.append("Connection: ").append(isHead ? "keep-alive" : "close").append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (isHead || len <= 0) return isHead;

        InputStream data = src.open(start);
        try {
            byte[] buf = new byte[64 * 1024];
            long remaining = len;
            while (remaining > 0) {
                int n = data.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
        } finally {
            try { data.close(); } catch (IOException ignored) { }
        }
        return false;
    }

    /** Parses "bytes=a-b", "bytes=a-" and "bytes=-n". Returns null if unsatisfiable. */
    static long[] parseRange(String header, long total) {
        String v = header.trim().toLowerCase(Locale.US);
        if (!v.startsWith("bytes=")) return new long[]{0, total - 1};
        v = v.substring(6);
        int comma = v.indexOf(',');
        if (comma >= 0) v = v.substring(0, comma); // only first range is served
        int dash = v.indexOf('-');
        if (dash < 0) return new long[]{0, total - 1};
        String a = v.substring(0, dash).trim(), b = v.substring(dash + 1).trim();
        try {
            long start, end;
            if (a.isEmpty()) {
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                start = Math.max(0, total - n);
                end = total - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
            }
            if (start >= total || start > end) return null;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return new long[]{0, total - 1};
        }
    }

    private static void simple(OutputStream out, int code, String text) throws IOException {
        String body = code + " " + text;
        String h = "HTTP/1.1 " + code + " " + text + "\r\nContent-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
        out.write(h.getBytes(StandardCharsets.US_ASCII));
    }

    /** Minimal HTTP request reader. */
    static final class Request {
        String method;
        String path;
        final java.util.Map<String, String> headers = new java.util.HashMap<>();

        String header(String name) { return headers.get(name.toLowerCase(Locale.US)); }

        static Request read(InputStream in) throws IOException {
            String line = readLine(in);
            if (line == null) return null;
            while (line.isEmpty()) { // tolerate stray CRLF between requests
                line = readLine(in);
                if (line == null) return null;
            }
            String[] parts = line.split(" ");
            if (parts.length < 2) throw new IOException("Bad request line: " + line);
            Request r = new Request();
            r.method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];
            if (target.startsWith("http://")) { // absolute-form
                int slash = target.indexOf('/', 7);
                target = slash < 0 ? "/" : target.substring(slash);
            }
            r.path = target;
            while (true) {
                String h = readLine(in);
                if (h == null || h.isEmpty()) break;
                int c = h.indexOf(':');
                if (c > 0) {
                    r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
                }
            }
            return r;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 8192) throw new IOException("Header line too long");
            }
            if (c == -1 && sb.length() == 0) return null;
            return sb.toString();
        }
    }
}
