package app.tvcast;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import app.tvcast.dlna.MediaSource;

/** A video picked from the phone (gallery, files app, share sheet) exposed to the HTTP server. */
final class UriSource implements MediaSource {
    final Uri uri;
    private final ContentResolver resolver;
    private final String name;
    private final String mime;
    private final long length;

    private UriSource(ContentResolver resolver, Uri uri, String name, String mime, long length) {
        this.resolver = resolver;
        this.uri = uri;
        this.name = name;
        this.mime = mime;
        this.length = length;
    }

    static UriSource from(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getApplicationContext().getContentResolver();
        String name = null;
        long size = -1;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (RuntimeException ignored) {
            // Some providers don't support queries; fall back below.
        } finally {
            if (c != null) c.close();
        }
        if (size <= 0) {
            ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("Can't open this video");
            try { size = pfd.getStatSize(); } finally { pfd.close(); }
        }
        if (size <= 0) throw new IOException("Can't read the size of this video");
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
            if (name == null) name = "Video";
        }
        String mime = cr.getType(uri);
        if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) mime = guessMime(name);
        return new UriSource(cr, uri, name, mime, size);
    }

    static String guessMime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".ts")) return "video/mp2t";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        return "video/mp4";
    }

    @Override public String name() { return name; }
    @Override public String mimeType() { return mime; }
    @Override public long length() { return length; }

    @Override public InputStream open(long offset) throws IOException {
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Video is no longer available");
        FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        try {
            if (offset > 0) in.getChannel().position(offset);
            return in;
        } catch (IOException e) {
            // Not seekable (e.g. a cloud file served through a pipe): reopen and skip.
            in.close();
        }
        InputStream s = resolver.openInputStream(uri);
        if (s == null) throw new IOException("Video is no longer available");
        long left = offset;
        while (left > 0) {
            long n = s.skip(left);
            if (n <= 0) {
                if (s.read() < 0) break;
                n = 1;
            }
            left -= n;
        }
        return s;
    }
}
