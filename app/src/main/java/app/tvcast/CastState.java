package app.tvcast;

import java.util.ArrayList;
import java.util.List;

import app.tvcast.dlna.Renderer;

/** Process-wide state shared by the screen and the background service. */
final class CastState {
    private CastState() { }

    static final List<Renderer> renderers = new ArrayList<>();
    static volatile Renderer selected;
    static volatile UriSource media;

    /** What is currently on the TV (null when nothing is being cast). */
    static volatile Renderer castingTo;
    static volatile UriSource castingMedia;

    static synchronized boolean addRenderer(Renderer r) {
        for (Renderer x : renderers) if (x.id().equals(r.id())) return false;
        // Samsung TVs first, then alphabetical.
        int i = 0;
        while (i < renderers.size()) {
            Renderer x = renderers.get(i);
            if (r.isSamsung() && !x.isSamsung()) break;
            if (r.isSamsung() == x.isSamsung() && r.displayName().compareToIgnoreCase(x.displayName()) < 0) break;
            i++;
        }
        renderers.add(i, r);
        return true;
    }

    static synchronized List<Renderer> renderers() { return new ArrayList<>(renderers); }

    static synchronized Renderer byLocation(String location) {
        for (Renderer x : renderers) if (x.location.equals(location)) return x;
        return null;
    }
}
