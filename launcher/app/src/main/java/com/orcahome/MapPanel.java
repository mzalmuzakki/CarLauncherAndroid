package com.orcahome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.view.View;

/**
 * A north-up map that keeps the car in the centre. Redrawn only when a GPS fix
 * arrives (about once a second) or a tile finishes loading.
 */
final class MapPanel extends View implements MapTiles.Listener {
    static final int MIN_ZOOM = 12, MAX_ZOOM = 18;

    // Dark map from the normal OpenStreetMap tiles: invert, turn the hues back
    // (so water stays blue and main roads orange), dim to 80 % on a navy base.
    private static final float[] NIGHT = {
        0.459f, -1.144f, -0.115f, 0, 214,
        -0.341f, -0.344f, -0.115f, 0, 218,
        -0.341f, -1.144f, 0.685f, 0, 234,
        0, 0, 0, 1, 0,
    };

    private final Ui ui;
    private final float tilePx;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint empty = new Paint();
    private final Paint arrowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accuracy = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint credit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private int zoom;
    private double lat = Double.NaN, lon;
    private float bearing, accuracyM;
    private boolean hasBearing, active;

    MapPanel(Context c, Ui ui, String style, int zoom) {
        super(c);
        this.ui = ui;
        if (MapTiles.STYLE_DARK.equals(style)) tilePaint.setColorFilter(new ColorMatrixColorFilter(NIGHT));
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        tilePx = 256 * c.getResources().getDisplayMetrics().density;
        empty.setColor(ui.tile);
        arrowFill.setColor(ui.accent);
        arrowEdge.setColor(0xFFFFFFFF);
        arrowEdge.setStyle(Paint.Style.STROKE);
        arrowEdge.setStrokeWidth(ui.dp(2));
        arrowEdge.setStrokeJoin(Paint.Join.ROUND);
        accuracy.setColor(ui.accent);
        accuracy.setAlpha(40);
        credit.setColor(ui.sub);
        credit.setTextSize(ui.dp(9));
        float s = ui.dp(13);
        arrow.moveTo(0, -s);
        arrow.lineTo(s * 0.8f, s);
        arrow.lineTo(0, s * 0.5f);
        arrow.lineTo(-s * 0.8f, s);
        arrow.close();
    }

    /** Tiles are only fetched while active (the home screen is visible). */
    void setActive(boolean a) {
        active = a;
        MapTiles.setActive(a);
        if (a) invalidate();
    }

    int zoom() { return zoom; }

    void zoomBy(int d) {
        int z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + d));
        if (z != zoom) { zoom = z; invalidate(); }
    }

    boolean hasFix() { return !Double.isNaN(lat); }

    void setLocation(Location l) {
        lat = l.getLatitude();
        lon = l.getLongitude();
        accuracyM = l.hasAccuracy() ? l.getAccuracy() : 0;
        // Bearing from GPS is only meaningful while moving.
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 1.5f) { bearing = l.getBearing(); hasBearing = true; }
        invalidate();
    }

    @Override
    public void onTileLoaded() {
        if (active) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, empty);
        if (Double.isNaN(lat)) return;

        double n = tilePx * (1 << zoom);
        double cx = (lon + 180) / 360 * n;
        double latR = Math.toRadians(lat);
        double cy = (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n;
        double left = cx - w / 2.0, top = cy - h / 2.0;
        int tiles = 1 << zoom;
        int tx0 = (int) Math.floor(left / tilePx), tx1 = (int) Math.floor((left + w) / tilePx);
        int ty0 = Math.max(0, (int) Math.floor(top / tilePx)), ty1 = Math.min(tiles - 1, (int) Math.floor((top + h) / tilePx));
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int x = ((tx % tiles) + tiles) % tiles;
                Bitmap b = active ? MapTiles.get(getContext(), zoom, x, ty, this) : null;
                if (b == null) continue;
                float px = (float) (tx * tilePx - left), py = (float) (ty * tilePx - top);
                c.save();
                c.translate(px, py);
                c.scale(tilePx / b.getWidth(), tilePx / b.getHeight());
                c.drawBitmap(b, 0, 0, tilePaint);
                c.restore();
            }
        }

        float mx = w / 2f, my = h / 2f;
        if (accuracyM > 15) {
            double metersPerPx = 156543.03392 * Math.cos(latR) / (1 << zoom) * 256 / tilePx;
            c.drawCircle(mx, my, (float) (accuracyM / metersPerPx), accuracy);
        }
        c.save();
        c.translate(mx, my);
        if (hasBearing) c.rotate(bearing);
        c.drawPath(arrow, arrowFill);
        c.drawPath(arrow, arrowEdge);
        c.restore();

        c.drawText(MapTiles.attribution(), ui.dp(8), h - ui.dp(6), credit);
    }
}
