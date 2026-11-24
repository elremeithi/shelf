package app.shelf.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sync folder pairs and the per-file state of the last two-way sync. */
public class Db extends SQLiteOpenHelper implements StateStore {
    public static class Pair {
        public long id;
        public String localUri = "", localName = "", remotePath = "/";
        public SyncEngine.Mode mode = SyncEngine.Mode.TWO_WAY;
        public int intervalMinutes = 60;   // 0 = only when started by hand
        public boolean wifiOnly = true, chargingOnly = false;
        public long lastRun;
        public String lastResult = "";
        public boolean lastOk = true;

        public String modeLabel() { return mode == SyncEngine.Mode.MOVE ? "Move to server" : "Two-way"; }
    }

    private static Db instance;

    public static synchronized Db get(Context ctx) {
        if (instance == null) instance = new Db(ctx.getApplicationContext());
        return instance;
    }

    private Db(Context ctx) { super(ctx, "shelf.db", null, 1); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pairs (id INTEGER PRIMARY KEY AUTOINCREMENT, local_uri TEXT, local_name TEXT, remote TEXT, mode TEXT," +
                " interval INTEGER, wifi INTEGER, charging INTEGER, last_run INTEGER DEFAULT 0, last_result TEXT DEFAULT '', last_ok INTEGER DEFAULT 1)");
        db.execSQL("CREATE TABLE state (pair INTEGER, path TEXT, ls INTEGER, lm INTEGER, rs INTEGER, rm INTEGER, PRIMARY KEY (pair, path))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    private static Pair read(Cursor c) {
        Pair p = new Pair();
        p.id = c.getLong(0);
        p.localUri = c.getString(1);
        p.localName = c.getString(2);
        p.remotePath = c.getString(3);
        p.mode = "MOVE".equals(c.getString(4)) ? SyncEngine.Mode.MOVE : SyncEngine.Mode.TWO_WAY;
        p.intervalMinutes = c.getInt(5);
        p.wifiOnly = c.getInt(6) != 0;
        p.chargingOnly = c.getInt(7) != 0;
        p.lastRun = c.getLong(8);
        p.lastResult = c.getString(9);
        p.lastOk = c.getInt(10) != 0;
        return p;
    }

    private static final String COLS = "id, local_uri, local_name, remote, mode, interval, wifi, charging, last_run, last_result, last_ok";

    public List<Pair> pairs() {
        List<Pair> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM pairs ORDER BY id", null)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    public Pair pair(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM pairs WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public long save(Pair p) {
        ContentValues v = new ContentValues();
        v.put("local_uri", p.localUri);
        v.put("local_name", p.localName);
        v.put("remote", p.remotePath);
        v.put("mode", p.mode.name());
        v.put("interval", p.intervalMinutes);
        v.put("wifi", p.wifiOnly ? 1 : 0);
        v.put("charging", p.chargingOnly ? 1 : 0);
        SQLiteDatabase db = getWritableDatabase();
        if (p.id == 0) p.id = db.insert("pairs", null, v);
        else db.update("pairs", v, "id=?", new String[]{String.valueOf(p.id)});
        return p.id;
    }

    public void setResult(long id, String result, boolean ok) {
        ContentValues v = new ContentValues();
        v.put("last_run", System.currentTimeMillis());
        v.put("last_result", result);
        v.put("last_ok", ok ? 1 : 0);
        getWritableDatabase().update("pairs", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("pairs", "id=?", new String[]{String.valueOf(id)});
        forget(id);
    }

    /** Drops the remembered state, so the next two-way sync treats both sides as new. */
    public void forget(long id) {
        getWritableDatabase().delete("state", "pair=?", new String[]{String.valueOf(id)});
    }

    @Override
    public Map<String, Rec> load(long pairId) {
        Map<String, Rec> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path, ls, lm, rs, rm FROM state WHERE pair=?", new String[]{String.valueOf(pairId)})) {
            while (c.moveToNext()) out.put(c.getString(0), new Rec(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4)));
        }
        return out;
    }

    @Override
    public void save(long pairId, Map<String, Rec> recs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("state", "pair=?", new String[]{String.valueOf(pairId)});
            ContentValues v = new ContentValues();
            for (Map.Entry<String, Rec> e : recs.entrySet()) {
                v.clear();
                v.put("pair", pairId);
                v.put("path", e.getKey());
                v.put("ls", e.getValue().localSize);
                v.put("lm", e.getValue().localMtime);
                v.put("rs", e.getValue().remoteSize);
                v.put("rm", e.getValue().remoteMtime);
                db.insert("state", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
