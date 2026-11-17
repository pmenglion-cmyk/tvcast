package app.tvcast.dlna;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A DLNA/UPnP MediaRenderer (e.g. a Samsung TV) and the AVTransport commands it understands. */
public final class Renderer {
    public final String location;
    public final String udn;
    public final String friendlyName;
    public final String manufacturer;
    public final String modelName;
    public final String avTransportUrl;
    public final String avTransportType;
    public final String renderingUrl;   // may be null
    public final String renderingType;  // may be null

    private Renderer(String location, String udn, String friendlyName, String manufacturer, String modelName,
                     String avTransportUrl, String avTransportType, String renderingUrl, String renderingType) {
        this.location = location;
        this.udn = udn;
        this.friendlyName = friendlyName;
        this.manufacturer = manufacturer;
        this.modelName = modelName;
        this.avTransportUrl = avTransportUrl;
        this.avTransportType = avTransportType;
        this.renderingUrl = renderingUrl;
        this.renderingType = renderingType;
    }

    /** Stable identity used to de-duplicate discovery replies. */
    public String id() { return udn.isEmpty() ? location : udn; }

    public boolean isSamsung() {
        return manufacturer.toLowerCase(Locale.US).contains("samsung");
    }

    public String host() {
        try { return new URL(location).getHost(); } catch (Exception e) { return ""; }
    }

    /** Label for the UI, e.g. "[TV] Samsung 7 Series (55)". */
    public String displayName() {
        String n = friendlyName.isEmpty() ? modelName : friendlyName;
        return n.isEmpty() ? host() : n;
    }

    @Override public String toString() { return displayName() + " (" + host() + ")"; }

    /** Downloads and parses a device description. Returns null if it has no AVTransport service. */
    public static Renderer fromDescription(String location) throws IOException {
        String xml = Soap.get(location);
        return parse(location, xml);
    }

    static Renderer parse(String location, String xml) throws IOException {
        String base = Soap.tag(xml, "URLBase");
        if (base.isEmpty()) base = location;
        String avUrl = null, avType = null, rcUrl = null, rcType = null;
        Matcher m = Pattern.compile("<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            String type = Soap.tag(block, "serviceType");
            String ctrl = Soap.tag(block, "controlURL");
            if (ctrl.isEmpty()) continue;
            if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:") && avUrl == null) {
                avUrl = resolve(base, ctrl);
                avType = type;
            } else if (type.startsWith("urn:schemas-upnp-org:service:RenderingControl:") && rcUrl == null) {
                rcUrl = resolve(base, ctrl);
                rcType = type;
            }
        }
        if (avUrl == null) return null;
        // The root device comes first; its friendlyName is the TV's name.
        return new Renderer(location, Soap.tag(xml, "UDN"), Soap.tag(xml, "friendlyName"),
                Soap.tag(xml, "manufacturer"), Soap.tag(xml, "modelName"), avUrl, avType, rcUrl, rcType);
    }

    static String resolve(String base, String rel) throws IOException {
        try {
            return new URL(new URL(base), rel).toString();
        } catch (Exception e) {
            throw new IOException("Bad URL in description: " + rel);
        }
    }

    /** The phone's IP address on the network that leads to this TV. */
    public InetAddress localAddressTowards() throws IOException {
        DatagramSocket ds = new DatagramSocket();
        try {
            ds.connect(new InetSocketAddress(InetAddress.getByName(host()), 1900));
            InetAddress a = ds.getLocalAddress();
            if (a == null || a.isAnyLocalAddress()) throw new IOException("No route to the TV");
            return a;
        } finally {
            ds.close();
        }
    }

    // ---- AVTransport ----

    private String av(String action, String... kv) throws IOException {
        String[] all = new String[kv.length + 2];
        all[0] = "InstanceID";
        all[1] = "0";
        System.arraycopy(kv, 0, all, 2, kv.length);
        return Soap.call(avTransportUrl, avTransportType, action, Soap.args(all));
    }

    public void setUri(String url, String didlMetadata) throws IOException {
        av("SetAVTransportURI", "CurrentURI", url, "CurrentURIMetaData", didlMetadata);
    }

    public void play() throws IOException { av("Play", "Speed", "1"); }

    public void pause() throws IOException { av("Pause"); }

    public void stop() throws IOException { av("Stop"); }

    public void seek(long seconds) throws IOException {
        av("Seek", "Unit", "REL_TIME", "Target", formatTime(seconds));
    }

    /** @return {positionSeconds, durationSeconds}; -1 when unknown. */
    public long[] position() throws IOException {
        String r = av("GetPositionInfo");
        return new long[]{parseTime(Soap.tag(r, "RelTime")), parseTime(Soap.tag(r, "TrackDuration"))};
    }

    /** e.g. PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT. */
    public String transportState() throws IOException {
        return Soap.tag(av("GetTransportInfo"), "CurrentTransportState");
    }

    // ---- RenderingControl ----

    public boolean hasVolume() { return renderingUrl != null; }

    public int volume() throws IOException {
        String r = Soap.call(renderingUrl, renderingType, "GetVolume",
                Soap.args("InstanceID", "0", "Channel", "Master"));
        try { return Integer.parseInt(Soap.tag(r, "CurrentVolume").trim()); } catch (NumberFormatException e) { return -1; }
    }

    public void setVolume(int v) throws IOException {
        Soap.call(renderingUrl, renderingType, "SetVolume",
                Soap.args("InstanceID", "0", "Channel", "Master", "DesiredVolume", String.valueOf(Math.max(0, Math.min(100, v)))));
    }

    // ---- helpers ----

    /** DIDL-Lite metadata. Samsung TVs refuse SetAVTransportURI without it. */
    public static String didl(String title, String url, String mime, long size) {
        String cls = mime != null && mime.startsWith("audio/") ? "object.item.audioItem.musicTrack" : "object.item.videoItem";
        String protocol = "http-get:*:" + mime + ":" + MediaServer.DLNA_FEATURES;
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                + " xmlns:sec=\"http://www.sec.co.kr/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + Soap.escape(title) + "</dc:title>"
                + "<upnp:class>" + cls + "</upnp:class>"
                + "<res protocolInfo=\"" + Soap.escape(protocol) + "\""
                + (size > 0 ? " size=\"" + size + "\"" : "") + ">"
                + Soap.escape(url) + "</res></item></DIDL-Lite>";
    }

    public static String formatTime(long s) {
        if (s < 0) s = 0;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    /** Parses "H:MM:SS", "H:MM:SS.mmm" or "H:MM:SS.F1/F2"; -1 if not available. */
    public static long parseTime(String t) {
        if (t == null) return -1;
        t = t.trim();
        if (t.isEmpty() || t.startsWith("NOT_")) return -1;
        int dot = t.indexOf('.');
        if (dot >= 0) t = t.substring(0, dot);
        String[] p = t.split(":");
        try {
            long v = 0;
            for (String x : p) v = v * 60 + Long.parseLong(x.trim().replace("+", ""));
            return v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
