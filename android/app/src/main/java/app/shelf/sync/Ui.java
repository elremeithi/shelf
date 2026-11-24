package app.shelf.sync;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small helpers for building the screens in code, in Shelf's colours. */
final class Ui {
    private Ui() {}

    static final ExecutorService IO = Executors.newCachedThreadPool();

    static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    static int color(Context c, int res) { return c.getColor(res); }

    static TextView text(Context c, CharSequence s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable rounded(Context c, int fillRes, int strokeRes, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(c, fillRes));
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeRes != 0) g.setStroke(dp(c, 1), color(c, strokeRes));
        return g;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFF1A1203 : color(c, R.color.text));
        GradientDrawable bg = rounded(c, primary ? R.color.accent : R.color.panel, primary ? 0 : R.color.line, 10);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    static EditText input(Context c, String hint, int type) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(color(c, R.color.text));
        e.setHintTextColor(color(c, R.color.dim));
        e.setBackground(rounded(c, R.color.panel, R.color.line, 10));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return e;
    }

    static EditText input(Context c, String hint) { return input(c, hint, InputType.TYPE_CLASS_TEXT); }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(rounded(c, R.color.panel, R.color.line, 14));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** A scrolling page with comfortable margins, which also stays readable on tablets. */
    static LinearLayout page(Activity a) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.setBackgroundColor(color(a, R.color.bg));
        LinearLayout outer = column(a);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout content = column(a);
        int p = dp(a, 16);
        content.setPadding(p, dp(a, 8), p, dp(a, 32));
        outer.addView(content, new LinearLayout.LayoutParams(Math.min(a.getResources().getDisplayMetrics().widthPixels, dp(a, 640)), ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(outer);
        a.setContentView(sv);
        return content;
    }

    static LinearLayout.LayoutParams fill(Context c, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    static LinearLayout.LayoutParams weight(Context c, float leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(c, leftDp);
        return lp;
    }

    static void add(LinearLayout parent, View v, float topDp) { parent.addView(v, fill(parent.getContext(), topDp)); }

    static String ago(long t) {
        if (t <= 0) return "never";
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    static String size(long n) {
        if (n < 1000) return n + " B";
        String[] u = {"kB", "MB", "GB", "TB"};
        double v = n;
        int i = -1;
        do { v /= 1000; i++; } while (v >= 1000 && i < u.length - 1);
        return (v < 10 ? String.format(java.util.Locale.ROOT, "%.1f", v) : String.valueOf(Math.round(v))) + " " + u[i];
    }
}
