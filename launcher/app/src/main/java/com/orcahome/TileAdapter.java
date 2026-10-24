package com.orcahome;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** App tiles (icon + name). An entry of null is the "+" tile that opens all apps. */
final class TileAdapter extends BaseAdapter {
    private final Context ctx;
    private final Ui ui;
    private final int tileHeight, iconSize;
    private final float labelSp;
    private final boolean boxed;
    private List<Apps.Entry> items = new ArrayList<>();

    TileAdapter(Context ctx, Ui ui, int tileHeightDp, int iconDp, float labelSp, boolean boxed) {
        this.ctx = ctx;
        this.ui = ui;
        this.tileHeight = ui.dp(tileHeightDp);
        this.iconSize = ui.dp(iconDp);
        this.labelSp = labelSp;
        this.boxed = boxed;
    }

    void set(List<Apps.Entry> list) {
        items = list;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public Apps.Entry getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int pos, View convert, ViewGroup parent) {
        Holder h;
        if (convert == null) {
            LinearLayout tile = new LinearLayout(ctx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
            if (boxed) tile.setBackground(ui.box(ui.tile));
            tile.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileHeight));
            h = new Holder();
            h.icon = new ImageView(ctx);
            tile.addView(h.icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            h.name = ui.label(ctx, labelSp, ui.text, false);
            h.name.setGravity(Gravity.CENTER);
            h.name.setSingleLine(true);
            h.name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(6);
            tile.addView(h.name, lp);
            tile.setTag(h);
            convert = tile;
        } else {
            h = (Holder) convert.getTag();
        }
        Apps.Entry e = items.get(pos);
        if (e == null) {
            h.icon.setTag(null);
            h.icon.setImageResource(R.drawable.ic_apps);
            h.icon.setColorFilter(ui.sub);
            h.icon.setPadding(iconSize / 5, iconSize / 5, iconSize / 5, iconSize / 5);
            h.icon.setBackground(ui.box(ui.tile, iconSize / 2));
            h.name.setText("All apps");
        } else {
            h.icon.clearColorFilter();
            h.icon.setPadding(0, 0, 0, 0);
            h.icon.setBackground(null);
            Apps.icon(h.icon, e);
            h.name.setText(e.label);
        }
        return convert;
    }

    private static final class Holder {
        ImageView icon;
        TextView name;
    }
}
