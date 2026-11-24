package app.shelf.sync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Server address and login. */
public class LoginActivity extends Activity {
    private EditText url, user, pass;
    private TextView error;
    private Button go;
    private ProgressBar busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(Prefs.configured(this) ? "Server" : "Connect to Shelf");
        LinearLayout page = Ui.page(this);

        Ui.add(page, Ui.text(this, "Enter the address you open Shelf with in a browser, and the login set on the server.", 15, R.color.dim, false), 8);
        url = Ui.input(this, "http://192.168.1.10:8080", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = Ui.input(this, "Username (leave empty if Shelf has no login)");
        pass = Ui.input(this, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setImeOptions(EditorInfo.IME_ACTION_GO);
        pass.setOnEditorActionListener((v, id, e) -> { connect(); return true; });
        url.setText(Prefs.url(this));
        user.setText(Prefs.user(this));
        Ui.add(page, Ui.text(this, "Server address", 13, R.color.dim, true), 20);
        Ui.add(page, url, 6);
        Ui.add(page, Ui.text(this, "Login", 13, R.color.dim, true), 18);
        Ui.add(page, user, 6);
        Ui.add(page, pass, 8);
        error = Ui.text(this, "", 14, R.color.danger, false);
        Ui.add(page, error, 12);
        busy = new ProgressBar(this);
        busy.setVisibility(View.GONE);
        Ui.add(page, busy, 8);
        go = Ui.button(this, "Connect", true);
        go.setOnClickListener(v -> connect());
        Ui.add(page, go, 12);
        Ui.add(page, Ui.text(this, "Tip: over the internet, use an https:// address behind a reverse proxy.", 13, R.color.dim, false), 18);
    }

    private void connect() {
        String u = url.getText().toString().trim();
        if (u.isEmpty()) { error.setText("Enter the server address."); return; }
        String name = user.getText().toString().trim(), pw = pass.getText().toString();
        error.setText("");
        busy.setVisibility(View.VISIBLE);
        go.setEnabled(false);
        Ui.IO.execute(() -> {
            String msg = null;
            ShelfClient c = new ShelfClient(u, name, pw, null);
            try { c.connect(); }
            catch (Exception e) { msg = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fail = msg;
            runOnUiThread(() -> {
                busy.setVisibility(View.GONE);
                go.setEnabled(true);
                if (fail != null) { error.setText("Could not connect: " + fail); return; }
                Prefs.save(this, c.baseUrl(), name, pw, c.token() == null ? "" : c.token());
                // Start over on the (possibly new) server's root folder.
                startActivity(new Intent(this, BrowseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            });
        });
    }
}
