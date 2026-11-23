package dev.fawpie.cherrymanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public final class SessionService extends Service {
    static final String STOP = "dev.fawpie.cherrymanager.STOP_SESSION";
    static volatile String active = "";
    static volatile String message = "";
    private static final String CHANNEL = "kernel_sessions";
    private static final int NOTIFICATION = 31;

    private HandlerThread thread;
    private Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::observe;
    private KernelClient kernel;
    private boolean ownsSession;
    private String kind;
    private String sensor;
    private int threshold;
    private int cap;
    private boolean limited;
    private int absent;
    private String applied = "balanced";
    private String foreground = "";
    private long lastScan;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("Cherry profile session");
        thread.start();
        worker = new Handler(thread.getLooper());
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Cherry Manager sessions", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            worker.removeCallbacks(poll);
            worker.post(() -> finishSession("Session stopped."));
            return START_NOT_STICKY;
        }
        if (!active.isEmpty()) return START_NOT_STICKY;
        String requested = intent.getStringExtra("mode");
        if (!"apps".equals(requested) && !"thermal".equals(requested)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        active = requested;
        message = "Starting";
        startForeground(NOTIFICATION, notification());
        worker.post(() -> begin(intent, requested));
        return START_NOT_STICKY;
    }

    private void begin(Intent intent, String requested) {
        try {
            kernel = KernelClient.get(this);
            KernelClient.Snapshot snapshot = kernel.read();
            if (!snapshot.canTune()) throw new IllegalStateException("This session needs root access and a compatible kernel.");
            if (!snapshot.session.isEmpty()) throw new IllegalStateException("Restore the previous session first.");
            kind = requested;
            if (kind.equals("apps")) {
                if (!CherryData.hasUsageAccess(this)) throw new IllegalStateException("Usage access is required for app profiles.");
                lastScan = System.currentTimeMillis() - 180_000;
            } else {
                sensor = intent.getStringExtra("sensor");
                threshold = intent.getIntExtra("threshold", 0);
                cap = intent.getIntExtra("cap", 0);
                KernelClient.Zone zone = snapshot.zones.get(sensor);
                if (zone == null || threshold < 40 || threshold > 85 || cap < 50 || cap > 90) {
                    throw new IllegalStateException("Choose an available thermal sensor and a valid threshold and cap.");
                }
                String name = zone.name.toLowerCase(java.util.Locale.ROOT);
                if (threshold > 45 && (name.contains("batt") || name.contains("skin") || name.equals("ac"))) {
                    throw new IllegalStateException("This sensor supports a threshold of up to 45 °C.");
                }
            }
            kernel.write("session-start", kind);
            ownsSession = true;
            CherryData.log(this, "Automatic session", kind.equals("apps") ? "App profiles started" : "Thermal monitoring started");
            observe();
        } catch (Exception failure) {
            finishSession(error(failure));
        }
    }

    private void observe() {
        if (!ownsSession) return;
        try {
            if (kind.equals("apps")) updateApp();
            else updateThermal();
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
            worker.postDelayed(poll, kind.equals("apps") ? 2_000 : 4_000);
        } catch (Exception failure) {
            finishSession(error(failure));
        }
    }

    private void updateApp() throws Exception {
        if (!CherryData.hasUsageAccess(this)) throw new IllegalStateException("Usage access was turned off.");
        long now = System.currentTimeMillis() - 500;
        if (now < lastScan) lastScan = now - 180_000;
        UsageEvents events = getSystemService(UsageStatsManager.class).queryEvents(lastScan, now);
        lastScan = now;
        if (events != null) {
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) foreground = event.getPackageName();
                else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED && foreground.equals(event.getPackageName())) foreground = "";
            }
        }
        boolean interactive = getSystemService(PowerManager.class).isInteractive();
        String wanted = interactive ? CherryData.settings(this).getString("rule." + foreground, "balanced") : "balanced";
        if (!wanted.equals(applied)) {
            kernel.write("session-profile", wanted);
            applied = wanted;
            CherryData.log(this, "App profile", wanted.equals("balanced")
                    ? "Restored and verified the original limits"
                    : CherryData.profileName(wanted) + " verified");
        }
        message = wanted.equals("balanced") ? "Waiting for an app rule" : CherryData.profileName(wanted) + " active";
    }

    private void updateThermal() throws Exception {
        KernelClient.Snapshot snapshot = kernel.read();
        if (!snapshot.canTune()) throw new IllegalStateException("Kernel write access was lost.");
        KernelClient.Zone zone = snapshot.zones.get(sensor);
        if (zone == null) {
            if (++absent >= 2) throw new IllegalStateException("The thermal sensor is no longer available.");
            return;
        }
        absent = 0;
        if (!limited && zone.celsius >= threshold) {
            kernel.write("session-cap", Integer.toString(cap));
            limited = true;
            CherryData.log(this, "Thermal cap", cap + "% cap verified");
        } else if (limited && zone.celsius <= threshold - 3) {
            kernel.write("session-cap", "100");
            limited = false;
            CherryData.log(this, "Thermal cap", "Restored and verified the original limits");
        }
        message = zone.name + " · " + Math.round(zone.celsius) + " °C · " + (limited ? "Cap active" : "Monitoring");
    }

    private void finishSession(String reason) {
        worker.removeCallbacks(poll);
        restore(reason);
        main.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void restore(String reason) {
        if (ownsSession) {
            try {
                kernel.write("session-restore");
                reason += " Original limits restored and verified.";
            } catch (Exception failure) {
                reason += " Restore pending. " + error(failure);
            }
            ownsSession = false;
        }
        if (!active.isEmpty()) CherryData.log(this, "Automatic session", reason);
        message = reason;
        active = "";
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SessionService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(active.equals("thermal") ? "Cherry · Thermal monitoring" : "Cherry · App profiles")
                .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private String error(Exception failure) {
        return failure.getMessage() == null ? "The session could not be completed." : failure.getMessage();
    }

    @Override public void onDestroy() {
        worker.removeCallbacks(poll);
        worker.post(() -> {
            if (ownsSession) restore("Session ended.");
            thread.quitSafely();
        });
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
