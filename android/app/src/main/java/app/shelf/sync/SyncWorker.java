package app.shelf.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Runs the sync of one pair (or all of them) in the background. */
public class SyncWorker extends Worker {
    static final int NOTIFY_PROGRESS = 1;
    static final String CH_PROGRESS = "progress", CH_RESULT = "result";

    /** What a running sync is doing, for the main screen. */
    public static class Live {
        public volatile String status = "Starting";
        public volatile String file = "";
        public volatile long done, total;
    }

    public static final Map<Long, Live> LIVE = new ConcurrentHashMap<>();
    /** Pairs the user started that the worker has not picked up yet. */
    public static final Set<Long> PENDING = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Long> STOP = Collections.newSetFromMap(new ConcurrentHashMap<>());

    static void requestStop(long id) {
        if (id < 0) STOP.addAll(LIVE.keySet());
        else STOP.add(id);
    }

    private long lastNotify;

    public SyncWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!Prefs.configured(ctx)) return Result.success();
        long only = getInputData().getLong("pair", -1);
        List<Db.Pair> pairs = new ArrayList<>();
        if (only < 0) pairs.addAll(Db.get(ctx).pairs());
        else { Db.Pair p = Db.get(ctx).pair(only); if (p != null) pairs.add(p); }
        if (pairs.isEmpty()) return Result.success();

        // A long sync keeps running as a foreground service; if Android refuses that
        // (some background starts), it still runs, and an interrupted upload resumes next time.
        try { setForegroundAsync(foregroundInfo("Starting sync", 0, 0)).get(); } catch (Exception ignored) {}

        boolean retry = false;
        for (Db.Pair p : pairs) {
            if (isStopped()) break;
            if (LIVE.putIfAbsent(p.id, new Live()) != null) continue; // already running
            PENDING.remove(p.id);
            STOP.remove(p.id);
            try {
                SyncEngine.Result r = runPair(ctx, p);
                boolean ok = r.fatal == null && r.failed == 0;
                Db.get(ctx).setResult(p.id, r.summary() + (r.errors.isEmpty() ? "" : "\n" + String.join("\n", r.errors.subList(0, Math.min(3, r.errors.size())))), ok);
                if (!ok && !"Stopped".equals(r.fatal)) notifyResult(ctx, p, r);
                if (r.fatal != null && !"Stopped".equals(r.fatal)) retry = true;
            } finally {
                LIVE.remove(p.id);
                STOP.remove(p.id);
            }
        }
        // Scheduled runs retry later on network failures; WorkManager backs off.
        return retry && !getInputData().getBoolean("manual", false) && getRunAttemptCount() < 3 ? Result.retry() : Result.success();
    }

    private SyncEngine.Result runPair(Context ctx, Db.Pair p) {
        Live live = LIVE.get(p.id);
        String label = p.localName + " → " + p.remotePath;
        ShelfClient client = Prefs.client(ctx);
        LocalStore local = new SafStore(ctx, Uri.parse(p.localUri));
        SyncEngine.Listener l = new SyncEngine.Listener() {
            @Override public void onStatus(String text) {
                live.status = text;
                live.file = "";
                live.done = live.total = 0;
                progress(label, text, 0, 0);
            }
            @Override public void onFile(String path, long done, long total) {
                live.file = path;
                live.done = done;
                live.total = total;
                progress(label, live.status, done, total);
            }
        };
        return new SyncEngine(client, local, Db.get(ctx), l, () -> isStopped() || STOP.contains(p.id)).run(p.id, p.remotePath, p.mode);
    }

    private void progress(String title, String text, long done, long total) {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 800) return;
        lastNotify = now;
        try { setForegroundAsync(foregroundInfo(title + "\n" + text, done, total)); } catch (Exception ignored) {}
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() { return foregroundInfo("Syncing", 0, 0); }

    private ForegroundInfo foregroundInfo(String text, long done, long total) {
        Context ctx = getApplicationContext();
        channels(ctx);
        String[] lines = text.split("\n", 2);
        Notification.Builder b = new Notification.Builder(ctx, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(lines[0])
                .setContentText(lines.length > 1 ? lines[1] : "")
                .setStyle(new Notification.BigTextStyle().bigText(lines.length > 1 ? lines[1] : ""))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx));
        if (total > 0) b.setProgress(1000, (int) (done * 1000 / total), false);
        else b.setProgress(0, 0, true);
        if (Build.VERSION.SDK_INT >= 29) return new ForegroundInfo(NOTIFY_PROGRESS, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return new ForegroundInfo(NOTIFY_PROGRESS, b.build());
    }

    private static void notifyResult(Context ctx, Db.Pair p, SyncEngine.Result r) {
        channels(ctx);
        String text = r.summary() + (r.errors.isEmpty() ? "" : "\n" + r.errors.get(0));
        Notification n = new Notification.Builder(ctx, CH_RESULT)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Sync problem: " + p.localName)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build();
        try { ctx.getSystemService(NotificationManager.class).notify((int) (100 + p.id), n); } catch (SecurityException ignored) {}
    }

    static PendingIntent openApp(Context ctx) {
        // Opens the sync page, with the file browser behind it for Back.
        return android.app.TaskStackBuilder.create(ctx)
                .addNextIntentWithParentStack(new Intent(ctx, MainActivity.class))
                .getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void channels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_PROGRESS, "Sync progress", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_RESULT, "Sync problems", NotificationManager.IMPORTANCE_DEFAULT));
    }
}
