package app.tvcast.dlna;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UPnP SOAP calls and small XML helpers (no XML library needed). */
public final class Soap {
    private Soap() { }

    public static final int CONNECT_TIMEOUT = 4000;
    public static final int READ_TIMEOUT = 8000;

    /** Raised when the TV answers with a UPnP error, e.g. 701 "Transition not available". */
    public static final class UpnpException extends IOException {
        public final int code;
        public UpnpException(int code, String description) {
            super("TV error " + code + (description == null || description.isEmpty() ? "" : ": " + description));
            this.code = code;
        }
    }

    public static Map<String, String> args(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Invokes {@code action} and returns the response body (XML). */
    public static String call(String controlUrl, String serviceType, String action,
                              Map<String, String> args) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            .append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">");
        for (Map.Entry<String, String> e : args.entrySet()) {
            body.append('<').append(e.getKey()).append('>')
                .append(escape(e.getValue()))
                .append("</").append(e.getKey()).append('>');
        }
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(controlUrl).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
            c.setRequestProperty("User-Agent", "Android UPnP/1.0 TVCast/1.0");
            c.setRequestProperty("Connection", "close");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.close();

            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = is == null ? "" : readAll(is);
            if (code >= 400) {
                String ec = tag(resp, "errorCode");
                String ed = tag(resp, "errorDescription");
                int n = -1;
                try { n = Integer.parseInt(ec.trim()); } catch (Exception ignored) { }
                if (n > 0) throw new UpnpException(n, ed);
                throw new IOException("TV answered HTTP " + code + " to " + action);
            }
            return resp;
        } finally {
            c.disconnect();
        }
    }

    public static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setRequestProperty("User-Agent", "Android UPnP/1.0 TVCast/1.0");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " for " + url);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 2 * 1024 * 1024) break;
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            is.close();
        }
    }

    /** First element named {@code name} (any namespace prefix), unescaped; "" if missing. */
    public static String tag(String xml, String name) {
        if (xml == null) return "";
        Matcher m = Pattern.compile("<(?:[\\w.-]+:)?" + Pattern.quote(name) + "(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?"
                + Pattern.quote(name) + "\\s*>", Pattern.DOTALL).matcher(xml);
        return m.find() ? unescape(m.group(1).trim()) : "";
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&");
    }
}
