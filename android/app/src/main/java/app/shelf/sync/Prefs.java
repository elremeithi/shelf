package app.shelf.sync;

import android.content.Context;
import android.content.SharedPreferences;

/** The server connection. Stored in the app's private storage, which other apps cannot read. */
public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("server", Context.MODE_PRIVATE); }

    public static boolean configured(Context c) { return !sp(c).getString("url", "").isEmpty(); }

    public static String url(Context c) { return sp(c).getString("url", ""); }

    public static String user(Context c) { return sp(c).getString("user", ""); }

    public static String token(Context c) { return sp(c).getString("token", ""); }

    public static void save(Context c, String url, String user, String password, String token) {
        sp(c).edit().putString("url", url).putString("user", user).putString("pass", password).putString("token", token).apply();
    }

    public static void clear(Context c) { sp(c).edit().clear().apply(); }

    public static ShelfClient client(Context c) {
        SharedPreferences p = sp(c);
        ShelfClient client = new ShelfClient(p.getString("url", ""), p.getString("user", ""), p.getString("pass", ""), p.getString("token", ""));
        Context app = c.getApplicationContext();
        client.setTokenListener(t -> sp(app).edit().putString("token", t).apply());
        return client;
    }
}
