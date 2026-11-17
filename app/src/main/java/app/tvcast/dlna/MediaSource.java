package app.tvcast.dlna;

import java.io.IOException;
import java.io.InputStream;

/** Something the built-in HTTP server can stream to the TV. */
public interface MediaSource {
    /** Display name, e.g. "holiday.mp4". */
    String name();

    /** MIME type, e.g. "video/mp4". */
    String mimeType();

    /** Total size in bytes. */
    long length();

    /** Opens a stream positioned at {@code offset}. Called once per HTTP request. */
    InputStream open(long offset) throws IOException;
}
