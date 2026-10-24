package com.orcahome;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every launchable app, with a search box. Long-press to add/remove from home. */
public class AllAppsActivity extends Activity {
    private Prefs prefs;
    private TileAdapter tiles;
    private String query = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        Ui ui = new Ui(this, prefs.activeTheme(this));
        ui.applyWindow(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        root.setBackgroundColor(ui.bg);

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setTextColor(ui.text);
        search.setHintTextColor(ui.sub);
        search.setBackground(ui.box(ui.panel, ui.dp(10)));
        search.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = ui.dp(12);
        root.addView(search, slp);

        GridView grid = new GridView(this);
        grid.setColumnWidth(ui.dp(116));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(ui.dp(10));
        grid.setVerticalSpacing(ui.dp(10));
        grid.setSelector(android.R.color.transparent);
        tiles = new TileAdapter(this, ui, 112, 52, 13, true);
        grid.setAdapter(tiles);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Apps.launch(this, tiles.getItem(pos).cn);
            finish();
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            menu(v, tiles.getItem(pos));
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refresh();
    }

    private void refresh() {
        List<Apps.Entry> shown = new ArrayList<>();
        for (Apps.Entry e : Apps.all(this)) {
            if (query.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(query)) shown.add(e);
        }
        tiles.set(shown);
    }

    private void menu(android.view.View anchor, Apps.Entry e) {
        final List<String> keys = new ArrayList<>();
        for (Apps.Entry f : Apps.favorites(this, prefs)) keys.add(f.key());
        final boolean onHome = keys.contains(e.key());
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, onHome ? "Remove from home" : "Add to home");
        m.getMenu().add(0, 2, 0, "App info");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 2) {
                Apps.openAppInfo(this, e.cn.getPackageName());
                return true;
            }
            if (onHome) keys.remove(e.key());
            else keys.add(e.key());
            prefs.setFavorites(keys);
            if (!onHome && prefs.showMap() && keys.size() > Apps.mapTiles(prefs)) {
                Toast.makeText(this, "Added, but beside the map there is room for only the first "
                        + Apps.mapTiles(prefs) + " apps: remove one from home to make room", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, onHome ? "Removed from home" : "Added to home", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        m.show();
    }
}
