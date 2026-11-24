package app.shelf.sync;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Puts sync pairs on WorkManager: automatic runs on a schedule, and "sync now". */
public final class Scheduler {
    private Scheduler() {}

    public static void schedule(Context ctx, Db.Pair p) {
        WorkManager wm = WorkManager.getInstance(ctx);
        if (p.intervalMinutes <= 0) {
            wm.cancelUniqueWork("auto-" + p.id);
            return;
        }
        Constraints c = new Constraints.Builder()
                .setRequiredNetworkType(p.wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .setRequiresCharging(p.chargingOnly)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(SyncWorker.class, Math.max(15, p.intervalMinutes), TimeUnit.MINUTES)
                .setConstraints(c)
                .setInputData(new Data.Builder().putLong("pair", p.id).build())
                .addTag("sync")
                .build();
        wm.enqueueUniquePeriodicWork("auto-" + p.id, ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    public static void scheduleAll(Context ctx) {
        for (Db.Pair p : Db.get(ctx).pairs()) schedule(ctx, p);
    }

    /** Runs one pair now (or every pair with id -1), on any network. */
    public static void runNow(Context ctx, long pairId) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(new Data.Builder().putLong("pair", pairId).putBoolean("manual", true).build())
                .addTag("sync")
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork("now-" + pairId, ExistingWorkPolicy.KEEP, req);
    }

    public static void stop(Context ctx, long pairId) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.cancelUniqueWork("now-" + pairId);
        wm.cancelUniqueWork("now--1");
        SyncWorker.requestStop(pairId);
    }

    public static void remove(Context ctx, long pairId) {
        stop(ctx, pairId);
        WorkManager.getInstance(ctx).cancelUniqueWork("auto-" + pairId);
    }
}
