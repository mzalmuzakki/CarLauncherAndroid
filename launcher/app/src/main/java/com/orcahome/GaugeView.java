package com.orcahome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * A 240-degree speed arc with the number in the middle. Drawn only when the
 * speed changes (at most once a second from GPS), so it costs nothing idle.
 */
final class GaugeView extends View {
    private static final float MAX_KMH = 160f;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int speed = -1; // -1 = no GPS fix yet
    private String hint = "";

    GaugeView(Context c, Ui ui) {
        super(c);
        float stroke = ui.dp(10);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(ui.panel); // the gauge sits on a ui.tile background
        fill.set(track);
        fill.setColor(ui.accent);
        big.setColor(ui.text);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        small.setColor(ui.sub);
        small.setTextAlign(Paint.Align.CENTER);
    }

    void setSpeed(int kmh) {
        if (kmh == speed) return;
        speed = kmh;
        invalidate();
    }

    void setHint(String h) {
        if (h.equals(hint)) return;
        hint = h;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h * 1.15f);
        float pad = track.getStrokeWidth();
        float left = (w - size) / 2 + pad, top = pad;
        arc.set(left, top, left + size - 2 * pad, top + size - 2 * pad);
        c.drawArc(arc, 150, 240, false, track);
        if (speed > 0) c.drawArc(arc, 150, 240 * Math.min(1f, speed / MAX_KMH), false, fill);

        float cx = arc.centerX(), cy = arc.centerY();
        big.setTextSize(size * 0.26f);
        small.setTextSize(size * 0.085f);
        c.drawText(speed < 0 ? "--" : String.valueOf(speed), cx, cy + size * 0.08f, big);
        c.drawText("km/h", cx, cy + size * 0.2f, small);
        if (!hint.isEmpty()) c.drawText(hint, cx, cy + size * 0.33f, small);
    }
}
