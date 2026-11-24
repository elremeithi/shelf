package app.shelf.sync;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/** The Shelf web interface (gallery, player, file manager) inside the app, already signed in. */
public class BrowseActivity extends Activity {
    private static final int PICK_FILES = 2;
    private WebView web;
    private FrameLayout root;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenDone;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);

        String base = Prefs.client(this).baseUrl();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Stay inside Shelf; anything else opens in the browser.
                if (r.getUrl().toString().startsWith(base)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Upload to Shelf"), PICK_FILES); }
                catch (Exception e) { fileCallback = null; return false; }
                return true;
            }

            // Fullscreen video and the viewer's fullscreen button.
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (fullscreen != null) { cb.onCustomViewHidden(); return; }
                fullscreen = view;
                fullscreenDone = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                systemBars(false);
            }

            @Override
            public void onHideCustomView() {
                if (fullscreen == null) return;
                root.removeView(fullscreen);
                fullscreen = null;
                web.setVisibility(View.VISIBLE);
                systemBars(true);
                if (fullscreenDone != null) fullscreenDone.onCustomViewHidden();
            }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) r.addRequestHeader("Cookie", cookie);
                String name = URLUtil.guessFileName(url, disposition, mime);
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                getSystemService(DownloadManager.class).enqueue(r);
                Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        // Refresh the login, hand it to the web view as Shelf's session cookie, then open the page.
        Ui.IO.execute(() -> {
            ShelfClient c = Prefs.client(this);
            try { c.connect(); } catch (Exception ignored) {} // the page shows its own login if this fails
            runOnUiThread(() -> {
                String t = c.token();
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                if (t != null && !t.isEmpty()) cm.setCookie(base, "shelf=" + t + "; path=/");
                cm.flush();
                web.loadUrl(base + "/");
            });
        });
    }

    @SuppressWarnings("deprecation")
    private void systemBars(boolean show) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (show) c.show(WindowInsets.Type.systemBars());
            else {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(show ? 0 : View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_FILES || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                out = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) out = new Uri[]{data.getData()};
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (fullscreen != null) web.getWebChromeClient().onHideCustomView();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
