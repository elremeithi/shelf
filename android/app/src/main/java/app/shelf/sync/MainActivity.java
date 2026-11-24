package app.shelf.sync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The list of sync folders, their status, and "sync now". */
public class MainActivity extends Activity {
    private LinearLayout list;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Holder> holders = new ArrayList<>();
    private String shown = "";

    private static class Holder {
        long id;
        TextView status;
        ProgressBar bar;
        Button sync;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.configured(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setTitle("Sync folders");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        LinearLayout page = Ui.page(this);

        TextView where = Ui.text(this, Prefs.url(this) + (Prefs.user(this).isEmpty() ? "" : "  ·  " + Prefs.user(this)), 14, R.color.dim, false);
        Ui.add(page, where, 4);

        list = Ui.column(this);
        Ui.add(page, list, 8);

        Button add = Ui.button(this, "+  Add a sync folder", true);
        add.setOnClickListener(v -> startActivity(new Intent(this, PairActivity.class)));
        Ui.add(page, add, 16);

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list == null) return;
        build();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void build() {
        list.removeAllViews();
        holders.clear();
        shown = "";
        List<Db.Pair> pairs = Db.get(this).pairs();
        if (pairs.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "No sync folders yet", 17, R.color.text, true));
            Ui.add(c, Ui.text(this, "Pair a folder on this phone with a folder on your Shelf server.\n\n"
                    + "Two-way: new and changed files are copied both ways. Deleting a file never deletes it on the other side.\n\n"
                    + "Move to server: files are uploaded, checked, and then removed from the phone to free space.\n\n"
                    + "Files on the server are never deleted by a sync.", 14, R.color.dim, false), 8);
            Ui.add(list, c, 8);
            return;
        }
        for (Db.Pair p : pairs) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, p.localName, 17, R.color.text, true));
            Ui.add(c, Ui.text(this, "→ " + p.remotePath + " on the server", 14, R.color.text, false), 2);
            String sched = p.intervalMinutes <= 0 ? "Only when started" : "Every " + interval(p.intervalMinutes)
                    + (p.wifiOnly ? ", on Wi-Fi" : "") + (p.chargingOnly ? ", while charging" : "");
            Ui.add(c, Ui.text(this, p.modeLabel() + "  ·  " + sched, 13, R.color.dim, false), 4);

            Holder h = new Holder();
            h.id = p.id;
            h.status = Ui.text(this, "", 14, R.color.dim, false);
            Ui.add(c, h.status, 10);
            h.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            h.bar.setMax(1000);
            h.bar.setVisibility(View.GONE);
            Ui.add(c, h.bar, 6);

            LinearLayout btns = Ui.row(this);
            btns.setGravity(Gravity.END);
            Button edit = Ui.button(this, "Settings", false);
            edit.setOnClickListener(v -> startActivity(new Intent(this, PairActivity.class).putExtra("id", p.id)));
            h.sync = Ui.button(this, "Sync now", true);
            h.sync.setOnClickListener(v -> {
                if (SyncWorker.LIVE.containsKey(p.id) || SyncWorker.PENDING.contains(p.id)) {
                    SyncWorker.PENDING.remove(p.id);
                    Scheduler.stop(this, p.id);
                    refreshStatus();
                    return;
                }
                if (p.mode == SyncEngine.Mode.MOVE) {
                    new AlertDialog.Builder(this)
                            .setTitle("Move files to the server?")
                            .setMessage("Files in " + p.localName + " are uploaded to " + p.remotePath + ". Each one is removed from the phone only after the server confirms it has an identical copy.")
                            .setPositiveButton("Move", (d, w) -> start(p.id))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else start(p.id);
            });
            btns.addView(edit);
            btns.addView(h.sync, Ui.weight(this, 8));
            Ui.add(c, btns, 12);
            holders.add(h);
            Ui.add(list, c, 10);
        }
        refreshStatus();
    }

    private void start(long id) {
        SyncWorker.PENDING.add(id); // show "Starting" until the worker picks it up
        Scheduler.runNow(this, id);
        // If it never starts (no network), stop showing it as starting.
        handler.postDelayed(() -> SyncWorker.PENDING.remove(id), 60_000);
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sig = new StringBuilder();
        for (Holder h : holders) {
            SyncWorker.Live live = SyncWorker.LIVE.get(h.id);
            if (live == null && SyncWorker.PENDING.contains(h.id)) {
                h.status.setText("Starting… (waits for a network connection)");
                h.status.setTextColor(Ui.color(this, R.color.text));
                h.bar.setVisibility(View.VISIBLE);
                h.bar.setIndeterminate(true);
                h.sync.setText("Stop");
            } else if (live != null) {
                String s = live.status;
                if (live.total > 0) s += "\n" + Ui.size(live.done) + " of " + Ui.size(live.total);
                h.status.setText(s);
                h.status.setTextColor(Ui.color(this, R.color.text));
                h.bar.setVisibility(View.VISIBLE);
                h.bar.setIndeterminate(live.total <= 0);
                if (live.total > 0) h.bar.setProgress((int) (live.done * 1000 / live.total));
                h.sync.setText("Stop");
            } else {
                Db.Pair p = Db.get(this).pair(h.id);
                if (p == null) continue;
                sig.append(p.lastRun);
                h.status.setText(p.lastRun == 0 ? "Not synced yet" : "Last sync " + Ui.ago(p.lastRun) + ": " + p.lastResult);
                h.status.setTextColor(Ui.color(this, p.lastOk ? R.color.dim : R.color.danger));
                h.bar.setVisibility(View.GONE);
                h.sync.setText("Sync now");
            }
        }
        shown = sig.toString();
    }

    static String interval(int m) {
        if (m < 60) return m + " minutes";
        if (m == 60) return "hour";
        if (m < 1440) return (m / 60) + " hours";
        return "day";
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Sync all now");
        menu.add(0, 2, 0, "Server and login");
        menu.add(0, 3, 0, "Sign out");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case 1:
                for (Db.Pair p : Db.get(this).pairs()) if (p.mode == SyncEngine.Mode.TWO_WAY) start(p.id);
                if (Db.get(this).pairs().stream().anyMatch(p -> p.mode == SyncEngine.Mode.MOVE))
                    new AlertDialog.Builder(this).setMessage("Two-way folders started. Folders set to \"Move to server\" are started one by one with their own Sync now button, so files are never removed from the phone by accident.").setPositiveButton("OK", null).show();
                return true;
            case 2:
                startActivity(new Intent(this, LoginActivity.class));
                return true;
            case 3:
                new AlertDialog.Builder(this)
                        .setTitle("Sign out?")
                        .setMessage("Automatic syncs stop until you sign in again. Your sync folders are kept.")
                        .setPositiveButton("Sign out", (d, w) -> {
                            for (Db.Pair p : Db.get(this).pairs()) Scheduler.remove(this, p.id);
                            Prefs.clear(this);
                            android.webkit.CookieManager.getInstance().removeAllCookies(null);
                            startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        })
                        .setNegativeButton("Cancel", null).show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
