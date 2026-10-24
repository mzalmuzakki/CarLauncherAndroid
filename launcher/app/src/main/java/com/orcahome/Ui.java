package com.orcahome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** The seven themes and small view helpers; the UI is built in code (no layouts). */
final class Ui {
    static final String[] THEME_NAMES = {
        "Light", "Dark", "Sky Glass", "Rose Dusk", "Arctic Drive", "Emerald Night", "Midnight Drive",
    };
    static final int DEFAULT_THEME = 6; // Midnight Drive

    // bg, panel, tile, text, sub, accent, onAccent
    private static final int[][] PALETTES = {
        {0xFFF3F5F8, 0xFFFFFFFF, 0xFFEFF2F6, 0xFF111827, 0xFF6B7280, 0xFF2563EB, 0xFFFFFFFF},
        {0xFF0A0A0B, 0xFF17181B, 0xFF222429, 0xFFF5F5F5, 0xFF9CA3AF, 0xFF3B82F6, 0xFFFFFFFF},
        {0xFF0D2236, 0xFF17344F, 0xFF1F4363, 0xFFEAF4FF, 0xFF9DB7CF, 0xFF4FC3F7, 0xFF06223A},
        {0xFF1E1418, 0xFF2B1D23, 0xFF39262E, 0xFFFBEFF3, 0xFFC9A9B4, 0xFFF48FB1, 0xFF2B1018},
        {0xFF172330, 0xFF223244, 0xFF2C4057, 0xFFEEF4FA, 0xFFA3B4C6, 0xFF26C6DA, 0xFF062A30},
        {0xFF0C1914, 0xFF14271F, 0xFF1C3529, 0xFFE8F5EF, 0xFF93B5A6, 0xFF34D399, 0xFF06281A},
        {0xFF070B1A, 0xFF111834, 0xFF1A2347, 0xFFE6EBFF, 0xFF8C96BF, 0xFF3D7BFF, 0xFFFFFFFF},
    };

    final int bg, panel, tile, text, sub, accent, onAccent;
    final int radius;
    final boolean light;
    private final float density;

    Ui(Context c, int theme) {
        density = c.getResources().getDisplayMetrics().density;
        int[] p = PALETTES[theme >= 0 && theme < PALETTES.length ? theme : DEFAULT_THEME];
        bg = p[0]; panel = p[1]; tile = p[2]; text = p[3]; sub = p[4]; accent = p[5]; onAccent = p[6];
        light = theme == 0;
        radius = dp(16);
    }

    int dp(float v) { return Math.round(v * density); }

    GradientDrawable box(int color) { return box(color, radius); }

    GradientDrawable box(int color, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    TextView label(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    ImageView icon(Context c, int res, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(color);
        return v;
    }

    /**
     * The CAKRA Labs logo on a transparent background: white lettering for the
     * dark themes, black for Light. Horizontal or stacked (mark above the name).
     */
    ImageView logo(Context c, int heightDp, boolean stacked) {
        ImageView v = new ImageView(c);
        int res = stacked
                ? (light ? R.drawable.cakra_stacked_light : R.drawable.cakra_stacked_dark)
                : (light ? R.drawable.cakra_horizontal_light : R.drawable.cakra_horizontal_dark);
        v.setImageResource(res);
        v.setAdjustViewBounds(true);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setMaxHeight(dp(heightDp));
        v.setContentDescription("CAKRA Labs");
        return v;
    }

    /** Window background and status bar icons to match the theme. */
    void applyWindow(Activity a) {
        a.getWindow().getDecorView().setBackgroundColor(bg);
        a.getWindow().setStatusBarColor(bg);
        a.getWindow().setNavigationBarColor(bg);
        View d = a.getWindow().getDecorView();
        int flags = d.getSystemUiVisibility();
        if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        d.setSystemUiVisibility(flags);
    }
}
