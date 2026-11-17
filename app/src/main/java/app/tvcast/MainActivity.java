package app.tvcast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.tvcast.dlna.Discovery;
import app.tvcast.dlna.MediaServer;
import app.tvcast.dlna.Renderer;
import app.tvcast.dlna.Soap;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIF = 2;
    private static final String PREFS = "tvcast";
    private static final String PREF_LAST_TV = "last_tv_location";
    private static final String PREF_MANUAL_IPS = "manual_ips";

    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    /** One thread for TV commands so they reach the TV in the order they were tapped. */
    private final ExecutorService tv = Executors.newSingleThreadExecutor();

    private TextView fileName, fileInfo, tvStatus, nowPlaying, timePos, timeDur, volLabel, log;
    private ProgressBar searchProgress;
    private RadioGroup tvList;
    private Button btnPlay, btnPause;
    private LinearLayout controls;
    private SeekBar seek;

    private volatile boolean searching;
    private volatile boolean resumed;
    private volatile boolean userSeeking;
    private volatile long lastPos = -1, lastDur = -1;
    private volatile String lastState = "";
    private Thread poller;

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        current = new WeakReference<>(this);

        fileName = (TextView) findViewById(R.id.file_name);
        fileInfo = (TextView) findViewById(R.id.file_info);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        searchProgress = (ProgressBar) findViewById(R.id.search_progress);
        tvList = (RadioGroup) findViewById(R.id.tv_list);
        btnPlay = (Button) findViewById(R.id.btn_play);
        controls = (LinearLayout) findViewById(R.id.controls);
        nowPlaying = (TextView) findViewById(R.id.now_playing);
        seek = (SeekBar) findViewById(R.id.seek);
        timePos = (TextView) findViewById(R.id.time_pos);
        timeDur = (TextView) findViewById(R.id.time_dur);
        btnPause = (Button) findViewById(R.id.btn_pause);
        volLabel = (TextView) findViewById(R.id.vol_label);
        log = (TextView) findViewById(R.id.log);

        findViewById(R.id.btn_choose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickVideo(); }
        });
        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startSearch(); }
        });
        findViewById(R.id.btn_add_ip).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askForIp(); }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { castNow(); }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePause(); }
        });
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { skip(-10); }
        });
        findViewById(R.id.btn_fwd).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { skip(30); }
        });
        findViewById(R.id.btn_vol_down).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { changeVolume(-2); }
        });
        findViewById(R.id.btn_vol_up).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { changeVolume(2); }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopCasting(); }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) timePos.setText(fmt(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                seekTo(s.getProgress());
            }
        });

        MediaServer.get().setListener(new MediaServer.Listener() {
            @Override public void onRequest(String clientIp, String range) { }
            @Override public void onError(String message) { logLine(message); }
        });

        handleIncoming(getIntent());
        showMedia();
        rebuildTvList();
        if (CastState.renderers().isEmpty()) {
            reconnectRemembered();
            startSearch();
        }
        askNotificationPermission();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
        showMedia();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        updateButtons();
        startPolling();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        resumed = false;
        tv.shutdown();
        super.onDestroy();
    }

    /** Called by the service when casting ends (stopped from the notification or idle). */
    static void notifyCastEnded() {
        final MainActivity a = current.get();
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                a.lastState = "";
                a.updateButtons();
            }
        });
    }

    // ---------------------------------------------------------------- video

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("video/*");
            startActivityForResult(Intent.createChooser(g, "Choose a video"), REQ_PICK);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Only possible for ACTION_OPEN_DOCUMENT results; the temporary grant is enough.
        }
        setMedia(uri);
        showMedia();
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uri = (Uri) p;
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }
        if (uri != null) setMedia(uri);
    }

    private void setMedia(Uri uri) {
        try {
            CastState.media = UriSource.from(this, uri);
            logLine("Selected " + CastState.media.name());
        } catch (IOException | SecurityException e) {
            toast("Couldn't open that video: " + e.getMessage());
        }
    }

    private void showMedia() {
        UriSource m = CastState.media;
        if (m == null) {
            fileName.setText(R.string.no_video);
            fileInfo.setVisibility(View.GONE);
        } else {
            fileName.setText(m.name());
            fileInfo.setText(String.format(Locale.US, "%s · %s", humanSize(m.length()), m.mimeType()));
            fileInfo.setVisibility(View.VISIBLE);
        }
        updateButtons();
    }

    // ---------------------------------------------------------------- finding TVs

    private void startSearch() {
        if (searching) return;
        searching = true;
        searchProgress.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.searching);
        final WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        final WifiManager.MulticastLock lock = wm == null ? null : wm.createMulticastLock("tvcast:ssdp");
        if (lock != null) {
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Discovery.search(4000, discoveryListener);
                } finally {
                    if (lock != null && lock.isHeld()) lock.release();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            searching = false;
                            searchProgress.setVisibility(View.GONE);
                            showSearchResult();
                        }
                    });
                }
            }
        }, "tvcast-search").start();
    }

    private void showSearchResult() {
        int n = CastState.renderers().size();
        if (n == 0) {
            tvStatus.setText(R.string.no_tvs);
        } else {
            tvStatus.setText(n == 1 ? "1 TV found" : n + " TVs found");
        }
    }

    private final Discovery.Listener discoveryListener = new Discovery.Listener() {
        @Override public void onFound(final Renderer r) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (CastState.addRenderer(r)) {
                        logLine("Found " + r.displayName() + " (" + r.host() + ")");
                        rebuildTvList();
                    }
                }
            });
        }
        @Override public void onLog(String msg) { logLine(msg); }
    };

    /** Reconnects straight away to the last TV used and to TVs added by IP, without waiting for SSDP. */
    private void reconnectRemembered() {
        final String last = prefs().getString(PREF_LAST_TV, null);
        final String ips = prefs().getString(PREF_MANUAL_IPS, "");
        new Thread(new Runnable() {
            @Override public void run() {
                if (last != null) {
                    try {
                        Renderer r = Renderer.fromDescription(last);
                        if (r != null) discoveryListener.onFound(r);
                    } catch (IOException ignored) { }
                }
                for (String ip : ips.split(",")) {
                    if (!ip.trim().isEmpty()) Discovery.probe(ip.trim(), discoveryListener);
                }
            }
        }, "tvcast-remembered").start();
    }

    private void askForIp() {
        final EditText input = new EditText(this);
        input.setHint("e.g. 192.168.1.23");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Add TV by IP address")
                .setMessage("On a Samsung TV: Settings → General → Network → Network Status → IP Settings.")
                .setView(wrap)
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        probeIp(input.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void probeIp(final String ip) {
        if (ip.isEmpty()) return;
        tvStatus.setText("Connecting to " + ip + "…");
        searchProgress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override public void run() {
                final Renderer r = Discovery.probe(ip, discoveryListener);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        searchProgress.setVisibility(searching ? View.VISIBLE : View.GONE);
                        if (r == null) {
                            tvStatus.setText("No TV answered at " + ip + ". Is the TV on and on the same Wi-Fi?");
                        } else {
                            rememberIp(ip);
                            selectRenderer(r);
                            showSearchResult();
                        }
                    }
                });
            }
        }, "tvcast-probe").start();
    }

    private void rememberIp(String ip) {
        String ips = prefs().getString(PREF_MANUAL_IPS, "");
        for (String x : ips.split(",")) if (x.equals(ip)) return;
        prefs().edit().putString(PREF_MANUAL_IPS, ips.isEmpty() ? ip : ips + "," + ip).apply();
    }

    private final RadioGroup.OnCheckedChangeListener tvChecked = new RadioGroup.OnCheckedChangeListener() {
        @Override public void onCheckedChanged(RadioGroup group, int checkedId) {
            View b = group.findViewById(checkedId);
            if (b != null && b.getTag() instanceof Renderer) {
                CastState.selected = (Renderer) b.getTag();
                prefs().edit().putString(PREF_LAST_TV, CastState.selected.location).apply();
                updateButtons();
            }
        }
    };

    private void rebuildTvList() {
        List<Renderer> list = CastState.renderers();
        tvList.setOnCheckedChangeListener(null);
        tvList.removeAllViews();
        String lastLoc = prefs().getString(PREF_LAST_TV, null);
        Renderer sel = CastState.selected;
        if (sel == null && lastLoc != null) sel = CastState.byLocation(lastLoc);
        if (sel == null && list.size() == 1) sel = list.get(0);
        int id = 1000;
        for (Renderer r : list) {
            RadioButton b = new RadioButton(this);
            b.setId(id++);
            b.setTag(r);
            String sub = r.isSamsung() ? "Samsung · " + r.host() : r.manufacturer + " · " + r.host();
            b.setText(r.displayName() + "\n" + sub);
            b.setTextColor(getResources().getColor(R.color.text));
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            b.setPadding(pad, pad, pad, pad);
            tvList.addView(b);
            if (sel != null && sel.id().equals(r.id())) {
                b.setChecked(true);
                CastState.selected = r;
            }
        }
        tvList.setOnCheckedChangeListener(tvChecked);
        if (!list.isEmpty() && !searching) showSearchResult();
        updateButtons();
    }

    private void selectRenderer(Renderer r) {
        CastState.addRenderer(r);
        CastState.selected = r;
        for (Renderer x : CastState.renderers()) {
            if (x.id().equals(r.id())) { CastState.selected = x; break; }
        }
        prefs().edit().putString(PREF_LAST_TV, CastState.selected.location).apply();
        rebuildTvList();
    }

    // ---------------------------------------------------------------- casting

    private void castNow() {
        final UriSource media = CastState.media;
        final Renderer r = CastState.selected;
        if (media == null) { toast("Choose a video first"); return; }
        if (r == null) { toast("Choose a TV first"); return; }

        CastState.castingTo = r;
        CastState.castingMedia = media;
        CastService.start(this);
        lastState = "TRANSITIONING";
        lastPos = 0;
        lastDur = -1;
        updateButtons();
        logLine("Sending " + media.name() + " to " + r.displayName() + "…");

        tv.execute(new Runnable() {
            @Override public void run() {
                try {
                    InetAddress local = r.localAddressTowards();
                    String url = MediaServer.get().publish(media, local);
                    logLine("Streaming from " + url);
                    try { r.stop(); } catch (IOException ignored) { }
                    r.setUri(url, Renderer.didl(media.name(), url, media.mimeType(), media.length()));
                    playWithRetry(r);
                    logLine("Playing on " + r.displayName());
                    startPolling();
                } catch (final IOException e) {
                    logLine("Failed: " + e.getMessage());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast(explain(e));
                            CastState.castingTo = null;
                            CastService.stop(MainActivity.this);
                            lastState = "";
                            updateButtons();
                        }
                    });
                }
            }
        });
    }

    /** TVs often answer 701 ("transition not available") while still loading the new video. */
    private static void playWithRetry(Renderer r) throws IOException {
        IOException last = null;
        for (int i = 0; i < 8; i++) {
            try {
                r.play();
                return;
            } catch (Soap.UpnpException e) {
                last = e;
                try { Thread.sleep(600); } catch (InterruptedException ie) { break; }
            }
        }
        throw last;
    }

    private static String explain(IOException e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof Soap.UpnpException) {
            int c = ((Soap.UpnpException) e).code;
            if (c == 714) return "The TV can't play this file type. Try an MP4 (H.264/AAC).";
            if (c == 716) return "The TV couldn't load the video from your phone. Make sure both are on the same Wi-Fi network (not a guest network).";
            if (c == 701) return "The TV is busy. Try again in a moment.";
        }
        if (m.contains("connect") || m.contains("timed out") || m.contains("No route")) {
            return "Can't reach the TV. Make sure it's on and on the same Wi-Fi as your phone.";
        }
        return "Couldn't play on the TV: " + m;
    }

    private void togglePause() {
        final Renderer r = CastState.castingTo;
        if (r == null) return;
        final boolean playing = "PLAYING".equals(lastState);
        tv.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (playing) r.pause(); else r.play();
                    lastState = playing ? "PAUSED_PLAYBACK" : "PLAYING";
                    runOnUiThread(new Runnable() { @Override public void run() { updateButtons(); } });
                } catch (IOException e) {
                    logLine("Pause/play failed: " + e.getMessage());
                }
            }
        });
    }

    private void skip(int seconds) {
        if (lastPos < 0) return;
        long target = Math.max(0, lastPos + seconds);
        if (lastDur > 0) target = Math.min(target, lastDur - 1);
        seekTo(target);
    }

    private void seekTo(final long seconds) {
        final Renderer r = CastState.castingTo;
        if (r == null) return;
        lastPos = seconds;
        tv.execute(new Runnable() {
            @Override public void run() {
                try {
                    r.seek(seconds);
                } catch (IOException e) {
                    logLine("Seek failed: " + e.getMessage());
                }
            }
        });
    }

    private void changeVolume(final int delta) {
        final Renderer r = CastState.castingTo != null ? CastState.castingTo : CastState.selected;
        if (r == null || !r.hasVolume()) return;
        tv.execute(new Runnable() {
            @Override public void run() {
                try {
                    int v = r.volume();
                    if (v < 0) return;
                    final int nv = Math.max(0, Math.min(100, v + delta));
                    r.setVolume(nv);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { volLabel.setText("Volume " + nv); }
                    });
                } catch (IOException e) {
                    logLine("Volume failed: " + e.getMessage());
                }
            }
        });
    }

    private void stopCasting() {
        final Renderer r = CastState.castingTo;
        tv.execute(new Runnable() {
            @Override public void run() {
                if (r != null) {
                    try { r.stop(); } catch (IOException ignored) { }
                }
            }
        });
        CastService.stop(this);
        CastState.castingTo = null;
        CastState.castingMedia = null;
        lastState = "";
        updateButtons();
        logLine("Stopped");
    }

    /** While casting, the phone's volume keys control the TV. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (CastState.castingTo != null && CastState.castingTo.hasVolume()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { changeVolume(2); return true; }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { changeVolume(-2); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- progress polling

    private synchronized void startPolling() {
        if (poller != null && poller.isAlive()) return;
        if (CastState.castingTo == null) return;
        poller = new Thread(new Runnable() {
            @Override public void run() {
                int failures = 0;
                while (resumed && CastState.castingTo != null) {
                    Renderer r = CastState.castingTo;
                    try {
                        long[] p = r.position();
                        String s = r.transportState();
                        failures = 0;
                        if (r == CastState.castingTo) {
                            lastPos = p[0];
                            if (p[1] > 0) lastDur = p[1];
                            lastState = s;
                            runOnUiThread(new Runnable() { @Override public void run() { showProgress(); } });
                        }
                    } catch (IOException e) {
                        if (++failures == 5) logLine("TV isn't answering: " + e.getMessage());
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            }
        }, "tvcast-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private void showProgress() {
        if (CastState.castingTo == null) return;
        if (lastDur > 0) {
            seek.setMax((int) lastDur);
            timeDur.setText(fmt(lastDur));
        }
        if (!userSeeking && lastPos >= 0) {
            seek.setProgress((int) lastPos);
            timePos.setText(fmt(lastPos));
        }
        updateButtons();
    }

    // ---------------------------------------------------------------- UI helpers

    private void updateButtons() {
        boolean casting = CastState.castingTo != null;
        btnPlay.setEnabled(CastState.media != null && CastState.selected != null);
        btnPlay.setText(casting ? R.string.play_again : R.string.play_on_tv);
        controls.setVisibility(casting ? View.VISIBLE : View.GONE);
        if (casting) {
            UriSource m = CastState.castingMedia;
            String state;
            switch (lastState) {
                case "PLAYING": state = "Playing"; break;
                case "PAUSED_PLAYBACK": state = "Paused"; break;
                case "TRANSITIONING": state = "Loading"; break;
                case "STOPPED": state = "Stopped"; break;
                case "NO_MEDIA_PRESENT": state = "Idle"; break;
                default: state = "Connecting";
            }
            nowPlaying.setText(state + " on " + CastState.castingTo.displayName()
                    + (m != null ? "\n" + m.name() : ""));
            btnPause.setText("PLAYING".equals(lastState) ? "Pause" : "Play");
            boolean hasVol = CastState.castingTo.hasVolume();
            findViewById(R.id.volume_row).setVisibility(hasVol ? View.VISIBLE : View.GONE);
        }
    }

    private void logLine(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                CharSequence old = log.getText();
                String text = s + (old.length() > 0 ? "\n" + old : "");
                if (text.length() > 3000) text = text.substring(0, 3000);
                log.setText(text);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    static String fmt(long s) {
        if (s < 0) return "--:--";
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String humanSize(long b) {
        if (b >= 1L << 30) return String.format(Locale.US, "%.1f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(Locale.US, "%.0f MB", b / (double) (1L << 20));
        return String.format(Locale.US, "%.0f KB", b / 1024.0);
    }
}
