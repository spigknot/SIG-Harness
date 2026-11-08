package com.sig.harness;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Visual language helpers: colors, spacing, rounded surfaces, big touch targets. */
public final class Ui {
    public static final int BG = Color.rgb(11, 15, 20);
    public static final int BG_ELEVATED = Color.rgb(15, 20, 27);
    public static final int SURFACE = Color.rgb(21, 27, 36);
    public static final int SURFACE_2 = Color.rgb(27, 35, 47);
    public static final int SURFACE_3 = Color.rgb(34, 44, 59);
    public static final int TEXT = Color.rgb(232, 238, 247);
    public static final int TEXT_SOFT = Color.rgb(196, 207, 222);
    public static final int MUTED = Color.rgb(139, 152, 171);
    public static final int FAINT = Color.rgb(96, 108, 126);
    public static final int ACCENT = Color.rgb(77, 124, 254);
    public static final int ACCENT_DARK = Color.rgb(42, 74, 168);
    public static final int ACCENT_SOFT = Color.rgb(28, 40, 71);
    public static final int GREEN = Color.rgb(61, 214, 140);
    public static final int RED = Color.rgb(255, 96, 96);
    public static final int AMBER = Color.rgb(255, 184, 76);
    public static final int CODE_BG = Color.rgb(10, 13, 18);

    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    public static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);
    public static final Typeface BLACK = Typeface.create("sans-serif-black", Typeface.BOLD);

    private Ui() { }

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    public static int sp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable round(int color, float radiusDp, float bottomLeft, float bottomRight, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dp(c, radiusDp);
        float bl = dp(c, bottomLeft);
        float br = dp(c, bottomRight);
        d.setCornerRadii(new float[]{r, r, r, r, br, br, bl, bl});
        return d;
    }

    public static GradientDrawable stroke(int fill, int strokeColor, int strokeWidthDp, float radiusDp, Context c) {
        GradientDrawable d = round(fill, radiusDp, c);
        d.setStroke(dp(c, strokeWidthDp), strokeColor);
        return d;
    }

    public static RippleDrawable ripple(int color, float radiusDp, Context c) {
        GradientDrawable content = round(color, radiusDp, c);
        GradientDrawable mask = round(Color.WHITE, radiusDp, c);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    public static RippleDrawable rippleStroke(int fill, int strokeColor, int strokeWidthDp, float radiusDp, Context c) {
        GradientDrawable content = stroke(fill, strokeColor, strokeWidthDp, radiusDp, c);
        GradientDrawable mask = round(Color.WHITE, radiusDp, c);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    public static TextView text(Context c, String value, float sizeSp, int color, Typeface tf) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setTypeface(tf == null ? REGULAR : tf);
        v.setIncludeFontPadding(false);
        return v;
    }

    public static TextView title(Context c, String value, float sizeSp) {
        return text(c, value, sizeSp, TEXT, MEDIUM);
    }

    public static TextView primaryButton(Context c, String label, View.OnClickListener click) {
        TextView v = button(c, label, ACCENT, Color.WHITE, 17f, 20f);
        v.setTypeface(MEDIUM);
        v.setCompoundDrawablePadding(dp(c, 8));
        v.setOnClickListener(click);
        return v;
    }

    public static TextView button(Context c, String label, int fill, int fg, float sizeSp, float radiusDp) {
        TextView v = text(c, label, sizeSp, fg, MEDIUM);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16));
        v.setMinHeight(dp(c, 56));
        v.setBackground(ripple(fill, radiusDp, c));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    public static TextView ghostButton(Context c, String label, View.OnClickListener click) {
        TextView v = button(c, label, Color.TRANSPARENT, TEXT_SOFT, 15f, 16f);
        v.setBackground(rippleStroke(Color.TRANSPARENT, SURFACE_3, 1, 16f, c));
        v.setOnClickListener(click);
        return v;
    }

    public static TextView iconButton(Context c, String glyph, float sizeSp, View.OnClickListener click) {
        TextView v = text(c, glyph, sizeSp, TEXT, REGULAR);
        v.setGravity(Gravity.CENTER);
        v.setBackground(ripple(Color.TRANSPARENT, 28f, c));
        v.setClickable(true);
        v.setFocusable(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        if (click != null) v.setOnClickListener(click);
        return v;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    public static LinearLayout.LayoutParams lpWeight(int width, int height, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.weight = weight;
        return p;
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    public static void margins(View v, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        m.setMargins(left, top, right, bottom);
        v.setLayoutParams(m);
    }

    public static void pad(View v, int dp, Context c) {
        int p = dp(c, dp);
        v.setPadding(p, p, p, p);
    }

    public static View divider(Context c, int color, int heightDp) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static View spacer(Context c, int dpHeight) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, dpHeight)));
        return v;
    }

    public static TextView statusDot(Context c, boolean online) {
        TextView v = text(c, online ? "•" : "•", 28f, online ? GREEN : FAINT, REGULAR);
        v.setIncludeFontPadding(false);
        return v;
    }

    public static void ellipsize(TextView v, int lines) {
        v.setMaxLines(lines);
        v.setEllipsize(TextUtils.TruncateAt.END);
    }

    public static String timeAgo(long millis) {
        if (millis <= 0) return "agora";
        long diff = System.currentTimeMillis() - millis;
        if (diff < 60000L) return "agora";
        if (diff < 3600000L) return (diff / 60000L) + " min";
        if (diff < 86400000L) return (diff / 3600000L) + " h";
        if (diff < 604800000L) return (diff / 86400000L) + " d";
        return android.text.format.DateFormat.format("dd/MM", millis).toString();
    }

    public static String shortPath(String path) {
        if (path == null) return "";
        String home = "/data/data/com.termux/files/home";
        if (path.equals(home)) return "~/";
        if (path.startsWith(home + "/")) return "~/" + path.substring(home.length() + 1);
        return path;
    }
}
