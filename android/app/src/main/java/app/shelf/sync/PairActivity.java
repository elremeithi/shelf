package app.shelf.sync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Creates or edits one sync pair: a phone folder, a server folder, and how they sync. */
public class PairActivity extends Activity {
    private static final int PICK = 1;
    private static final int[] INTERVALS = {0, 15, 60, 360, 1440};
    private static final String[] INTERVAL_LABELS = {"Only when I press Sync now", "Every 15 minutes", "Every hour", "Every 6 hours", "Once a day"};

    private Db.Pair pair;
    private TextView localLabel;
    private EditText remote;
    private RadioButton twoWay, move;
    private Spinner interval;
    private CheckBox wifi, charging;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        long id = getIntent().getLongExtra("id", 0);
        pair = id == 0 ? new Db.Pair() : Db.get(this).pair(id);
        if (pair == null) { finish(); return; }
        setTitle(id == 0 ? "New sync folder" : "Sync settings");
        LinearLayout page = Ui.page(this);

        // Phone folder
        Ui.add(page, Ui.text(this, "Folder on this phone", 13, R.color.dim, true), 12);
        LinearLayout lc = Ui.card(this);
        localLabel = Ui.text(this, pair.localName.isEmpty() ? "None chosen" : pair.localName, 16, R.color.text, false);
        lc.addView(localLabel);
        Button pick = Ui.button(this, "Choose phone folder", false);
        pick.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), PICK));
        Ui.add(lc, pick, 10);
        Ui.add(page, lc, 6);

        // Server folder
        Ui.add(page, Ui.text(this, "Folder on the server", 13, R.color.dim, true), 18);
        LinearLayout rc = Ui.card(this);
        remote = Ui.input(this, "/Phone/Camera", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        remote.setText(pair.remotePath);
        rc.addView(remote);
        Button browse = Ui.button(this, "Browse the server", false);
        browse.setOnClickListener(v -> new FolderPicker().show(remote.getText().toString()));
        Ui.add(rc, browse, 10);
        Ui.add(rc, Ui.text(this, "Folders that do not exist yet are created on the first sync.", 13, R.color.dim, false), 8);
        Ui.add(page, rc, 6);

        // Mode
        Ui.add(page, Ui.text(this, "How to sync", 13, R.color.dim, true), 18);
        LinearLayout mc = Ui.card(this);
        RadioGroup g = new RadioGroup(this);
        twoWay = radio("Two-way sync");
        move = radio("Move to server, then delete from the phone");
        g.addView(twoWay);
        g.addView(Ui.text(this, "New and changed files are copied both ways. Deleting a file on one side never deletes it on the other. When a phone edit replaces a server file, the old version is kept in a hidden .shelf-versions folder; if both sides changed a file, both copies are kept.", 13, R.color.dim, false));
        g.addView(move);
        g.addView(Ui.text(this, "Frees space on the phone. Each file is uploaded and checked (SHA-256) before it is removed from the phone. If the server already has a different file with that name, both are kept. Files on the server are never deleted.", 13, R.color.dim, false));
        (pair.mode == SyncEngine.Mode.MOVE ? move : twoWay).setChecked(true);
        mc.addView(g);
        Ui.add(page, mc, 6);

        // Schedule
        Ui.add(page, Ui.text(this, "When", 13, R.color.dim, true), 18);
        LinearLayout sc = Ui.card(this);
        interval = new Spinner(this);
        interval.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, INTERVAL_LABELS));
        int sel = 2;
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == pair.intervalMinutes) sel = i;
        interval.setSelection(sel);
        sc.addView(interval);
        wifi = check("Only on Wi-Fi", pair.wifiOnly);
        charging = check("Only while charging", pair.chargingOnly);
        Ui.add(sc, wifi, 6);
        sc.addView(charging);
        Ui.add(sc, Ui.text(this, "Automatic syncs also wait until the battery is not low. Sync now runs on any network.", 13, R.color.dim, false), 4);
        Ui.add(page, sc, 6);

        Button save = Ui.button(this, "Save", true);
        save.setOnClickListener(v -> save());
        Ui.add(page, save, 20);

        if (pair.id != 0) {
            Button forget = Ui.button(this, "Forget sync history", false);
            forget.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Forget sync history?")
                    .setMessage("The next two-way sync compares both folders from scratch: files deleted on one side since earlier syncs are copied back from the other side. Nothing is deleted.")
                    .setPositiveButton("Forget", (d, w) -> { Db.get(this).forget(pair.id); Toast.makeText(this, "Sync history cleared", Toast.LENGTH_SHORT).show(); })
                    .setNegativeButton("Cancel", null).show());
            Ui.add(page, forget, 24);
            Button del = Ui.button(this, "Remove this sync", false);
            del.setTextColor(Ui.color(this, R.color.danger));
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Remove this sync?")
                    .setMessage("Only the pairing is removed. No files are deleted on the phone or the server.")
                    .setPositiveButton("Remove", (d, w) -> {
                        Scheduler.remove(this, pair.id);
                        Db.get(this).delete(pair.id);
                        try { getContentResolver().releasePersistableUriPermission(Uri.parse(pair.localUri), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) {}
                        finish();
                    })
                    .setNegativeButton("Cancel", null).show());
            Ui.add(page, del, 8);
        }
    }

    private RadioButton radio(String label) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextSize(16);
        r.setTextColor(Ui.color(this, R.color.text));
        r.setId(View.generateViewId());
        r.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return r;
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(15);
        c.setTextColor(Ui.color(this, R.color.text));
        c.setChecked(on);
        return c;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Toast.makeText(this, "Android did not allow access to that folder", Toast.LENGTH_LONG).show();
            return;
        }
        pair.localUri = tree.toString();
        pair.localName = SafStore.describe(tree);
        localLabel.setText(pair.localName);
        String r = remote.getText().toString().trim();
        if (r.isEmpty() || r.equals("/")) {
            String leaf = pair.localName.substring(pair.localName.lastIndexOf('/') + 1).replace("Phone › ", "").replace("SD card › ", "");
            remote.setText("/Phone/" + (leaf.isEmpty() || leaf.equals("Phone") ? "Files" : leaf));
        }
    }

    private void save() {
        if (pair.localUri.isEmpty()) { Toast.makeText(this, "Choose a folder on the phone first", Toast.LENGTH_SHORT).show(); return; }
        String r;
        try { r = SyncEngine.normalize(remote.getText().toString().trim()); }
        catch (IllegalArgumentException e) { remote.setError("Not a valid folder"); return; }
        boolean modeChanged = pair.id != 0 && pair.mode != (move.isChecked() ? SyncEngine.Mode.MOVE : SyncEngine.Mode.TWO_WAY);
        boolean targetChanged = pair.id != 0 && !r.equals(pair.remotePath);
        pair.remotePath = r;
        pair.mode = move.isChecked() ? SyncEngine.Mode.MOVE : SyncEngine.Mode.TWO_WAY;
        pair.intervalMinutes = INTERVALS[interval.getSelectedItemPosition()];
        pair.wifiOnly = wifi.isChecked();
        pair.chargingOnly = charging.isChecked();
        if (pair.mode == SyncEngine.Mode.MOVE && pair.intervalMinutes > 0 && (pair.id == 0 || modeChanged)) {
            new AlertDialog.Builder(this)
                    .setTitle("Move files automatically?")
                    .setMessage("Every " + MainActivity.interval(pair.intervalMinutes) + ", files in " + pair.localName + " will be uploaded and then removed from the phone once the server confirms it has them.")
                    .setPositiveButton("Yes", (d, w) -> store(modeChanged || targetChanged))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        store(modeChanged || targetChanged);
    }

    private void store(boolean resetHistory) {
        Db db = Db.get(this);
        db.save(pair);
        if (resetHistory) db.forget(pair.id);
        Scheduler.schedule(this, pair);
        finish();
    }

    /** Browse server folders, create new ones, and choose one. */
    private class FolderPicker {
        private String path;
        private AlertDialog dialog;
        private final List<String> names = new ArrayList<>();
        private final List<String> real = new ArrayList<>(); // folder names behind the list rows
        private ArrayAdapter<String> adapter;
        private TextView title;
        private ProgressBar busy;

        void show(String start) {
            try { path = SyncEngine.normalize(start.isEmpty() ? "/" : start); } catch (Exception e) { path = "/"; }
            LinearLayout box = Ui.column(PairActivity.this);
            int p = Ui.dp(PairActivity.this, 16);
            box.setPadding(p, p / 2, p, 0);
            title = Ui.text(PairActivity.this, path, 15, R.color.text, true);
            box.addView(title);
            busy = new ProgressBar(PairActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            busy.setIndeterminate(true);
            box.addView(busy);
            ListView lv = new ListView(PairActivity.this);
            adapter = new ArrayAdapter<>(PairActivity.this, android.R.layout.simple_list_item_1, names);
            lv.setAdapter(adapter);
            lv.setOnItemClickListener((a, v, i, id) -> {
                String n = real.get(i);
                if (n == null) path = path.lastIndexOf('/') <= 0 ? "/" : path.substring(0, path.lastIndexOf('/'));
                else path = SyncEngine.join(path, n);
                load();
            });
            box.addView(lv, new LinearLayout.LayoutParams(-1, Ui.dp(PairActivity.this, 360)));
            dialog = new AlertDialog.Builder(PairActivity.this)
                    .setTitle("Server folder")
                    .setView(box)
                    .setPositiveButton("Use this folder", (d, w) -> remote.setText(path))
                    .setNeutralButton("New folder", null)
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> newFolder()));
            dialog.show();
            load();
        }

        void newFolder() {
            EditText name = Ui.input(PairActivity.this, "Folder name");
            LinearLayout wrap = Ui.column(PairActivity.this);
            int p = Ui.dp(PairActivity.this, 20);
            wrap.setPadding(p, p / 2, p, 0);
            wrap.addView(name);
            new AlertDialog.Builder(PairActivity.this)
                    .setTitle("New folder in " + path)
                    .setView(wrap)
                    .setPositiveButton("Create", (d, w) -> {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty() || n.contains("/")) return;
                        String target = SyncEngine.join(path, n);
                        Ui.IO.execute(() -> {
                            try {
                                Prefs.client(PairActivity.this).mkdirs(target);
                                runOnUiThread(() -> { path = target; load(); });
                            } catch (Exception e) {
                                runOnUiThread(() -> Toast.makeText(PairActivity.this, "Could not create the folder: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        });
                    })
                    .setNegativeButton("Cancel", null).show();
        }

        void load() {
            title.setText(path);
            busy.setVisibility(View.VISIBLE);
            final String at = path;
            Ui.IO.execute(() -> {
                List<String> out = new ArrayList<>();
                String err = null;
                try {
                    for (ShelfClient.Entry e : Prefs.client(PairActivity.this).list(at)) if (e.dir) out.add(e.name);
                    out.sort(String.CASE_INSENSITIVE_ORDER);
                } catch (ShelfClient.ApiException e) {
                    err = e.status == 404 ? "This folder does not exist yet. It will be created when you sync." : e.getMessage();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fail = err;
                runOnUiThread(() -> {
                    if (!at.equals(path)) return;
                    busy.setVisibility(View.GONE);
                    names.clear();
                    real.clear();
                    if (!at.equals("/")) { names.add("‹  Up"); real.add(null); }
                    for (String n : out) { names.add("📁  " + n); real.add(n); }
                    adapter.notifyDataSetChanged();
                    title.setText(fail == null ? path : path + "\n" + fail);
                });
            });
        }
    }
}
