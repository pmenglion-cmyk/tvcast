package app.tvcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;

import app.tvcast.dlna.MediaServer;
import app.tvcast.dlna.Renderer;

/**
 * Keeps the app alive while the TV is pulling the video from the phone, so casting
 * continues with the screen off or another app open. Stops itself once the TV is done.
 */
public class CastService extends Service {
    static final String ACTION_START = "app.tvcast.START";
    static final String ACTION_STOP = "app.tvcast.STOP";

    private static final String CHANNEL = "casting";
    private static final int NOTIFICATION_ID = 1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread watchdog;
    private static volatile boolean running;

    static boolean isRunning() { return running; }

    static void start(Context ctx) {
        Intent i = new Intent(ctx, CastService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CastService.class));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            final Renderer r = CastState.castingTo;
            if (r != null) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { r.stop(); } catch (IOException ignored) { }
                    }
                }).start();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        acquireLocks();
        startWatchdog();
        running = true;
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while a video is playing on your TV");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Renderer r = CastState.castingTo;
        UriSource m = CastState.castingMedia;
        b.setSmallIcon(R.drawable.ic_stat_cast)
         .setContentTitle(r != null ? "Playing on " + r.displayName() : "TV Cast")
         .setContentText(m != null ? m.name() : "Streaming from your phone")
         .setContentIntent(open)
         .setOngoing(true)
         .setShowWhen(false)
         .setCategory(Notification.CATEGORY_TRANSPORT)
         .addAction(new Notification.Action.Builder(
                 Icon.createWithResource(this, R.drawable.ic_stop), "Stop", stop).build());
        return b.build();
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvcast:stream");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvcast:stream");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
    }

    /** Stops the service once the TV has stopped (or vanished) for a while, to save battery. */
    private void startWatchdog() {
        if (watchdog != null && watchdog.isAlive()) return;
        watchdog = new Thread(new Runnable() {
            @Override public void run() {
                int idle = 0;
                try {
                    Thread.sleep(30000);
                    while (!Thread.currentThread().isInterrupted()) {
                        Renderer r = CastState.castingTo;
                        boolean active = false;
                        if (r != null) {
                            try {
                                String s = r.transportState();
                                active = s.equals("PLAYING") || s.equals("PAUSED_PLAYBACK")
                                        || s.equals("TRANSITIONING") || s.equals("PAUSED_RECORDING");
                            } catch (IOException e) {
                                active = false;
                            }
                        }
                        idle = active ? 0 : idle + 1;
                        if (idle >= 4) { // about a minute
                            stopSelf();
                            return;
                        }
                        Thread.sleep(15000);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }, "tvcast-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @Override public void onDestroy() {
        running = false;
        if (watchdog != null) watchdog.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        MediaServer.get().stop();
        CastState.castingTo = null;
        CastState.castingMedia = null;
        MainActivity.notifyCastEnded();
        super.onDestroy();
    }
}
