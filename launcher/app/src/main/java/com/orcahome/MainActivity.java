package com.orcahome;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen. Main panel: destination search (or the turn-by-turn card
 * while Google Maps / Waze navigates), app grid, clock and speed gauge.
 * Right column: calendar, weather and the music card. Dock along the bottom.
 *
 * Nothing here runs while the screen is not visible: GPS, the music progress
 * tick and weather all start in onResume and stop in onPause.
 */
public class MainActivity extends Activity implements NavState.Listener {
    private static final String[] PHONE_APPS = {"com.nwd.android.phone", "com.android.dialer", "com.google.android.dialer"};
    private static final String[][] PLACES = {
        {"Gas stations", "gas station"}, {"ATMs", "ATM"}, {"Restaurants", "restaurant"}, {"Hotels", "hotel"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Ui ui;
    private long builtStamp = -1;
    private boolean appsChanged, visible;

    private TileAdapter tiles;
    private GridView grid;
    private View searchArea, navCard, topArea;
    private EditText searchField;
    // Big guidance over the map while Google Maps / Waze navigates (map layouts).
    private View navBanner;
    private ImageView bannerArrow;
    private TextView bannerDistance, bannerInstruction, bannerEta;
    private boolean navWasActive;
    private ImageView navArrow;
    private TextView navDistance, navInstruction, navEta;
    private GaugeView gauge;
    private MapPanel map;
    private TextView speedBubble, mapHint;
    private LinearLayout favRow;
    private TextView calMonth, calDay, calPrev, calToday, calNext;
    private TextView wCond, wTemp, wCity, wIcon;
    private View musicCard;
    private ImageView musicArt, musicSourceIcon;
    private TextView musicSource, musicTitle, musicArtist, musicElapsed, musicDuration;
    private View progressFill;
    private ImageButton playPause;

    private LocationManager location;
    private MediaSessionManager sessions;
    private MediaController controller;
    /** True while the home screen is in front (BootReceiver checks it). */
    static volatile boolean shown;
    // Copies of the player's metadata and state, updated by its callbacks: every
    // controller.getMetadata() is a call into the system that copies the album art.
    private MediaMetadata meta, shownMeta;
    private PlaybackState playback;
    private boolean sessionsListening;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        location = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        shown = true;
        if (prefs.stamp() != builtStamp) { build(); appsChanged = false; }
        else if (appsChanged) reloadApps.run();
        IntentFilter t = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(dateChanged, t);
        updateCalendar();
        showWeather();
        if (prefs.showWeather()) Weather.refreshIfStale(this, prefs, this::showWeather);
        NavState.setListener(this);
        rebindListener();
        onNavChanged();
        if (map != null) map.setActive(true);
        startSpeed();
        startMusic();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        shown = false;
        hideKeyboard();
        unregisterReceiver(dateChanged);
        NavState.setListener(null);
        if (map != null) map.setActive(false);
        stopSpeed();
        stopMusic();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        main.removeCallbacks(reloadApps);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Already home.
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (grid != null) grid.smoothScrollToPosition(0);
    }

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction())
                    && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return; // an update: ADDED follows
            Apps.invalidate();
            appsChanged = true;
            // App updates come in bursts; reload once when they settle (or on return to home).
            main.removeCallbacks(reloadApps);
            if (visible) main.postDelayed(reloadApps, 2000);
        }
    };

    private final Runnable reloadApps = () -> Apps.preload(this, () -> {
        if (visible && appsChanged && !isDestroyed()) { refreshTiles(); appsChanged = false; }
    });

    private final BroadcastReceiver dateChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { updateCalendar(); }
    };

    // ---- layout ------------------------------------------------------------

    private void build() {
        builtStamp = prefs.stamp();
        ui = new Ui(this, prefs.theme());
        ui.applyWindow(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        LinearLayout content = new LinearLayout(this);
        content.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(8));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View mainPanel = mainPanel();
        content.addView(mainPanel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 62f));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 38f);
        rlp.leftMargin = ui.dp(10);
        content.addView(right, rlp);
        right.addView(infoRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(104)));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mlp.topMargin = ui.dp(10);
        right.addView(musicPanel(), mlp);

        root.addView(dock(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(60)));
        setContentView(root);
        refreshTiles();
        if (controller != null) showTrack();
    }

    private LinearLayout panel(int orientation) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(orientation);
        p.setBackground(ui.box(ui.panel));
        return p;
    }

    private View mainPanel() {
        LinearLayout p = panel(LinearLayout.VERTICAL);
        p.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8));

        FrameLayout top = new FrameLayout(this);
        topArea = top;
        searchArea = searchArea();
        top.addView(searchArea);
        navCard = navCard();
        navCard.setVisibility(View.GONE);
        top.addView(navCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(84)));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = ui.dp(8);
        if (prefs.showMap()) {
            grid = null;
            gauge = null;
            p.addView(mapBody(), blp);
            return p;
        }
        map = null;
        speedBubble = mapHint = null;
        favRow = null;
        navBanner = null;
        LinearLayout body = new LinearLayout(this);
        p.addView(body, blp);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(ui.dp(4));
        grid.setSelector(android.R.color.transparent);
        grid.setVerticalScrollBarEnabled(false);
        tiles = new TileAdapter(this, ui, 96, 48, 12, false);
        grid.setAdapter(tiles);
        grid.setOnItemClickListener((g, v, pos, id) -> {
            Apps.Entry e = tiles.getItem(pos);
            if (e == null) startActivity(new Intent(this, AllAppsActivity.class));
            else Apps.launch(this, e.cn);
        });
        grid.setOnItemLongClickListener((g, v, pos, id) -> {
            Apps.Entry e = tiles.getItem(pos);
            if (e != null) tileMenu(v, e);
            return true;
        });
        body.addView(grid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 56f));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setBackground(ui.box(ui.tile));
        side.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 44f);
        slp.leftMargin = ui.dp(10);
        body.addView(side, slp);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm a");
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.showGauge() ? 36 : 64);
        clock.setTextColor(ui.text);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        side.addView(clock);
        if (prefs.showGauge()) {
            gauge = new GaugeView(this, ui);
            gauge.setOnClickListener(v -> askLocation());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            glp.topMargin = ui.dp(6);
            side.addView(gauge, glp);
        } else {
            gauge = null;
            TextClock date = new TextClock(this);
            date.setFormat24Hour("EEEE, d MMMM");
            date.setFormat12Hour("EEEE, d MMMM");
            date.setTextColor(ui.sub);
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            side.addView(date);
        }
        return p;
    }

    /**
     * Live map with a speed bubble and zoom buttons, and the favourite apps: in a row below
     * (wide layout), or in two columns beside a square map (square-map layout).
     */
    private View mapBody() {
        boolean tall = "tall".equals(prefs.mapLayout());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(tall ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        favRow = new LinearLayout(this);
        favRow.setOrientation(tall ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        favRow.setGravity(Gravity.CENTER);
        if (tall) {
            FrameLayout square = new SquareFrame(this);
            square.addView(mapFrame(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            box.addView(square, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            fl.leftMargin = ui.dp(8);
            box.addView(favRow, fl);
        } else {
            box.addView(mapFrame(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(74));
            fl.topMargin = ui.dp(6);
            box.addView(favRow, fl);
        }
        showLastKnownLocation();
        return box;
    }

    private View mapFrame() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(ui.box(ui.tile));
        frame.setClipToOutline(true);
        String style = "classic".equals(prefs.mapStyle()) ? MapTiles.STYLE_CLASSIC
                : (ui.light ? MapTiles.STYLE_LIGHT : MapTiles.STYLE_DARK);
        map = new MapPanel(this, ui, style, prefs.mapZoom(), "heading".equals(prefs.mapDirection()));
        map.setOnClickListener(v -> {
            if (!locationAllowed() || !location.isProviderEnabled(LocationManager.GPS_PROVIDER)) askLocation();
            else openNavigation();
        });
        frame.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mapHint = ui.label(this, 14, ui.sub, false);
        mapHint.setGravity(Gravity.CENTER);
        frame.addView(mapHint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        if (prefs.showGauge()) {
            speedBubble = ui.label(this, 22, ui.text, true);
            speedBubble.setGravity(Gravity.CENTER);
            speedBubble.setLineSpacing(0, 0.9f);
            speedBubble.setBackground(ui.box(ui.panel, ui.dp(36)));
            speedBubble.setText("--\nkm/h");
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ui.dp(72), ui.dp(72), Gravity.TOP | Gravity.END);
            sl.topMargin = sl.rightMargin = ui.dp(10);
            frame.addView(speedBubble, sl);
        } else {
            speedBubble = null;
        }

        LinearLayout zoom = new LinearLayout(this);
        zoom.setOrientation(LinearLayout.VERTICAL);
        zoom.addView(zoomButton("+", 1));
        View gap = new View(this);
        zoom.addView(gap, new LinearLayout.LayoutParams(1, ui.dp(8)));
        zoom.addView(zoomButton("\u2212", -1));
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        zl.bottomMargin = zl.rightMargin = ui.dp(10);
        frame.addView(zoom, zl);

        navBanner = navBanner();
        navBanner.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        bl.leftMargin = bl.topMargin = ui.dp(10);
        bl.rightMargin = speedBubble != null ? ui.dp(92) : ui.dp(10);
        frame.addView(navBanner, bl);
        bannerEta = ui.label(this, 14, ui.text, true);
        bannerEta.setBackground(ui.box(ui.panel, ui.dp(16)));
        bannerEta.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
        bannerEta.setVisibility(View.GONE);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        el.leftMargin = ui.dp(10);
        el.bottomMargin = ui.dp(22);
        frame.addView(bannerEta, el);
        return frame;
    }

    /** Google Maps-style guidance: turn arrow, distance and street, from the navigation app. */
    private View navBanner() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(ui.box(0xFF1E7A4C, ui.dp(14)));
        c.setPadding(ui.dp(14), ui.dp(12), ui.dp(16), ui.dp(12));
        c.setElevation(ui.dp(4));
        bannerArrow = new ImageView(this);
        c.addView(bannerArrow, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(14), 0, 0, 0);
        bannerDistance = ui.label(this, 26, 0xFFFFFFFF, true);
        bannerInstruction = ui.label(this, 16, 0xFFFFFFFF, false);
        bannerInstruction.setMaxLines(2);
        bannerInstruction.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(bannerDistance);
        col.addView(bannerInstruction, wrapTop(2));
        c.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.setOnClickListener(v -> { if (mapsWindowOn()) openMapsWindow(); else openNavApp(); });
        return c;
    }

    // ---- experimental: Google Maps in a window over the map panel ----------------------

    /** Where Android's freeform windows are switched on (scripts/launcher.sh maps-window). */
    private boolean mapsWindowOn() {
        return map != null && prefs.mapsWindow()
                && Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0;
    }

    /** Opens the navigation app as a window exactly over the map panel. */
    private void openMapsWindow() {
        View frame = (View) map.getParent();
        int[] at = new int[2];
        frame.getLocationOnScreen(at);
        Rect r = new Rect(at[0], at[1], at[0] + frame.getWidth(), at[1] + frame.getHeight());
        String pkg = NavState.pkg;
        if (NavState.testApp && !NavState.isRealNavApp(pkg)) pkg = NavState.MAPS; // emulator test guidance
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { openNavApp(); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i, ActivityOptions.makeBasic().setLaunchBounds(r).toBundle());
        } catch (ActivityNotFoundException | SecurityException e) {
            openNavApp();
        }
    }

    /** Navigation ended: Orca Home to the front, so the Maps window goes behind it. */
    private void homeToFront() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (RuntimeException ignored) { }
    }

    private void openNavApp() {
        if (NavState.open != null) {
            try { NavState.open.send(); return; } catch (PendingIntent.CanceledException ignored) { }
        }
        launchPackage(NavState.pkg);
    }

    private View zoomButton(String label, int delta) {
        TextView b = ui.label(this, 22, ui.text, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ui.box(ui.panel, ui.dp(20)));
        b.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        b.setOnClickListener(v -> {
            map.zoomBy(delta);
            prefs.setMapZoom(map.zoom());
        });
        return b;
    }

    /** Shows where the car was last seen right away, before the first new GPS fix. */
    private void showLastKnownLocation() {
        if (!locationAllowed()) { mapHint.setText("Tap the map to allow GPS"); return; }
        Location best = null;
        try {
            for (String pr : location.getProviders(true)) {
                Location l = location.getLastKnownLocation(pr);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        if (best != null) { map.setLocation(best); mapHint.setText(""); }
        else mapHint.setText("Waiting for GPS\u2026");
    }

    /** The favourite apps beside the map (as many as fit), then "All apps". */
    private void fillFavRow() {
        favRow.removeAllViews();
        List<Apps.Entry> favs = new ArrayList<>(Apps.favorites(this, prefs));
        int max = Apps.mapTiles(prefs);
        if (favs.size() > max) favs = favs.subList(0, max);
        favs.add(null); // "All apps"
        if (favRow.getOrientation() == LinearLayout.HORIZONTAL) {
            for (Apps.Entry e : favs) favRow.addView(favTile(e));
            return;
        }
        // Square-map layout: rows of two, always four rows so the tiles keep their size.
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                if (i < favs.size()) row.addView(favTile(favs.get(i)));
                else row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            favRow.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    private View favTile(Apps.Entry e) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        int s = ui.dp(40);
        TextView name = ui.label(this, 11, ui.text, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        if (e == null) {
            icon.setImageResource(R.drawable.ic_apps);
            icon.setColorFilter(ui.sub);
            icon.setPadding(s / 5, s / 5, s / 5, s / 5);
            icon.setBackground(ui.box(ui.tile, s / 2));
            name.setText("All apps");
            t.setOnClickListener(v -> startActivity(new Intent(this, AllAppsActivity.class)));
        } else {
            Apps.icon(icon, e);
            name.setText(e.label);
            t.setOnClickListener(v -> Apps.launch(this, e.cn));
            t.setOnLongClickListener(v -> { tileMenu(v, e); return true; });
        }
        t.addView(icon, new LinearLayout.LayoutParams(s, s));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = ui.dp(4);
        t.addView(name, nl);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return t;
    }

    private View searchArea() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(ui.box(ui.tile, ui.dp(10)));
        bar.setPadding(ui.dp(12), 0, ui.dp(6), 0);
        ImageView s = ui.icon(this, R.drawable.ic_search, ui.sub);
        bar.addView(s, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        final EditText q = new EditText(this);
        searchField = q;
        q.setHint("Enter destination");
        q.setHintTextColor(ui.sub);
        q.setTextColor(ui.text);
        q.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        q.setSingleLine(true);
        q.setBackground(null);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setOnEditorActionListener((v, action, ev) -> {
            boolean go = action == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (go && q.getText().length() > 0) { searchPlace(q.getText().toString()); q.setText(""); }
            if (go) hideKeyboard();
            return go;
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qlp.leftMargin = ui.dp(8);
        bar.addView(q, qlp);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(42)));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        for (String[] place : PLACES) {
            TextView c = ui.label(this, 13, ui.text, false);
            c.setText(place[0]);
            c.setGravity(Gravity.CENTER);
            c.setPadding(ui.dp(12), 0, ui.dp(12), 0);
            c.setBackground(ui.box(ui.tile, ui.dp(15)));
            c.setOnClickListener(v -> searchPlace(place[1]));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(30));
            clp.rightMargin = ui.dp(8);
            chips.addView(c, clp);
        }
        hs.addView(chips);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = ui.dp(8);
        col.addView(hs, hlp);
        return col;
    }

    private View navCard() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(ui.box(ui.accent));
        c.setPadding(ui.dp(14), ui.dp(8), ui.dp(16), ui.dp(8));
        navArrow = new ImageView(this);
        c.addView(navArrow, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(14), 0, ui.dp(8), 0);
        navDistance = ui.label(this, 22, ui.onAccent, true);
        navInstruction = ui.label(this, 15, ui.onAccent, false);
        navInstruction.setSingleLine(true);
        navInstruction.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(navDistance);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = ui.dp(4);
        col.addView(navInstruction, ilp);
        c.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        navEta = ui.label(this, 13, ui.onAccent, false);
        navEta.setGravity(Gravity.END);
        navEta.setMaxWidth(ui.dp(170));
        c.addView(navEta);
        c.setOnClickListener(v -> openNavApp());
        return c;
    }

    private View infoRow() {
        LinearLayout row = new LinearLayout(this);

        LinearLayout cal = panel(LinearLayout.VERTICAL);
        cal.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        calMonth = ui.label(this, 11, ui.sub, false);
        calDay = ui.label(this, 14, ui.text, true);
        cal.addView(calMonth);
        cal.addView(calDay, wrapTop(3));
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.BOTTOM);
        calPrev = ui.label(this, 22, ui.sub, false);
        calToday = ui.label(this, 28, ui.accent, true);
        calNext = ui.label(this, 22, ui.sub, false);
        strip.addView(calPrev);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = tlp.rightMargin = ui.dp(10);
        strip.addView(calToday, tlp);
        strip.addView(calNext);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
        cal.addView(strip, slp);
        strip.setGravity(Gravity.BOTTOM | Gravity.START);
        if (prefs.showMap()) {
            // The map replaces the clock panel, so this card shows the date and a large clock.
            LinearLayout dc = panel(LinearLayout.VERTICAL);
            dc.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(8));
            calMonth = ui.label(this, 11, ui.sub, false);
            dc.addView(calMonth);
            dc.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
            TextClock clock = new TextClock(this);
            clock.setFormat24Hour("HH:mm");
            clock.setFormat12Hour("h:mm");
            clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 40);
            clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            clock.setTextColor(ui.text);
            clock.setIncludeFontPadding(false);
            dc.addView(clock);
            calDay = calPrev = calToday = calNext = null;
            row.addView(dc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            row.addView(cal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        if (prefs.showWeather()) {
            FrameLayout w = new FrameLayout(this);
            w.setBackground(ui.box(ui.panel));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            TextView head = ui.label(this, 11, ui.sub, false);
            head.setText("WEATHER");
            col.addView(head);
            // Three lines fit the 104 dp card: header, temperature, "condition · city".
            wTemp = ui.label(this, 28, ui.accent, true);
            wTemp.setIncludeFontPadding(false);
            col.addView(wTemp, wrapTop(8));
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            wCond = ui.label(this, 13, ui.text, true);
            wCond.setSingleLine(true);
            line.addView(wCond);
            wCity = ui.label(this, 12, ui.sub, false);
            wCity.setSingleLine(true);
            wCity.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(wCity);
            col.addView(line, wrapTop(6));
            w.addView(col);
            wIcon = ui.label(this, 22, ui.text, false);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            ilp.topMargin = ui.dp(8);
            ilp.rightMargin = ui.dp(10);
            w.addView(wIcon, ilp);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            wlp.leftMargin = ui.dp(10);
            row.addView(w, wlp);
        } else {
            wCond = wTemp = wCity = wIcon = null;
        }
        return row;
    }

    private LinearLayout.LayoutParams wrapTop(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(dp);
        return lp;
    }

    private View musicPanel() {
        LinearLayout p = panel(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        musicSourceIcon = new ImageView(this);
        head.addView(musicSourceIcon, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        musicSource = ui.label(this, 11, ui.sub, false);
        musicSource.setSingleLine(true);
        musicSource.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.leftMargin = ui.dp(8);
        head.addView(musicSource, hlp);
        p.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        musicArt = new ImageView(this);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setBackground(ui.box(ui.tile, ui.dp(10)));
        musicArt.setClipToOutline(true);
        // Square cover with flexible space around it, so any card height looks balanced.
        p.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        p.addView(musicArt, new LinearLayout.LayoutParams(ui.dp(120), ui.dp(120)));

        musicTitle = ui.label(this, 15, ui.text, true);
        musicTitle.setSingleLine(true);
        musicTitle.setEllipsize(TextUtils.TruncateAt.END);
        musicTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = ui.dp(8);
        p.addView(musicTitle, tlp);
        musicArtist = ui.label(this, 13, ui.sub, false);
        musicArtist.setSingleLine(true);
        musicArtist.setEllipsize(TextUtils.TruncateAt.END);
        musicArtist.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp2.topMargin = ui.dp(3);
        p.addView(musicArtist, alp2);
        p.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        FrameLayout bar = new FrameLayout(this);
        View track = new View(this);
        track.setBackground(ui.box(ui.tile, ui.dp(2)));
        bar.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4), Gravity.CENTER_VERTICAL));
        progressFill = new View(this);
        progressFill.setBackground(ui.box(ui.accent, ui.dp(2)));
        progressFill.setPivotX(0);
        progressFill.setScaleX(0);
        bar.addView(progressFill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4), Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(12));
        blp.topMargin = ui.dp(8);
        p.addView(bar, blp);
        LinearLayout times = new LinearLayout(this);
        musicElapsed = ui.label(this, 11, ui.sub, false);
        musicDuration = ui.label(this, 11, ui.sub, false);
        times.addView(musicElapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(musicDuration);
        p.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(mediaButton(R.drawable.ic_prev, false, v -> { if (controller != null) controller.getTransportControls().skipToPrevious(); }));
        playPause = mediaButton(R.drawable.ic_play, true, v -> togglePlay());
        buttons.addView(playPause);
        buttons.addView(mediaButton(R.drawable.ic_next, false, v -> { if (controller != null) controller.getTransportControls().skipToNext(); }));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = ui.dp(6);
        p.addView(buttons, bl);

        p.setOnClickListener(v -> openPlayer());
        musicCard = p;
        shownMeta = null;
        showTrack();
        return p;
    }

    private ImageButton mediaButton(int icon, boolean primary, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setColorFilter(primary ? ui.onAccent : ui.text);
        b.setBackground(ui.box(primary ? ui.accent : ui.tile, ui.dp(24)));
        b.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(primary ? 52 : 44), ui.dp(primary ? 52 : 44));
        lp.leftMargin = lp.rightMargin = ui.dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private View dock() {
        LinearLayout d = new LinearLayout(this);
        d.setGravity(Gravity.CENTER_VERTICAL);
        d.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(4));
        dockButton(d, R.drawable.ic_home, true, v -> { if (grid != null) grid.smoothScrollToPosition(0); }, null);
        dockButton(d, R.drawable.ic_phone, false, v -> openPhone(), null);
        dockButton(d, R.drawable.ic_apps, false, v -> startActivity(new Intent(this, AllAppsActivity.class)), null);
        dockButton(d, R.drawable.ic_music, false, v -> openMusic(), null);
        dockButton(d, R.drawable.ic_navigation, false, v -> openNavigation(), null);
        dockButton(d, R.drawable.ic_globe, false, v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))), null);
        dockButton(d, R.drawable.ic_settings, false, v -> startActivity(new Intent(this, SettingsActivity.class)),
                v -> { open(new Intent(Settings.ACTION_SETTINGS)); return true; });
        d.addView(ui.logo(this, 30, false), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(30)));
        return d;
    }

    private void dockButton(LinearLayout d, int icon, boolean active, View.OnClickListener l, View.OnLongClickListener ll) {
        ImageView b = ui.icon(this, icon, active ? ui.accent : ui.text);
        b.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setOnClickListener(l);
        if (ll != null) b.setOnLongClickListener(ll);
        d.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    // ---- tiles ---------------------------------------------------------------

    private void refreshTiles() {
        if (favRow != null) { fillFavRow(); return; }
        List<Apps.Entry> list = new ArrayList<>(Apps.favorites(this, prefs));
        list.add(null); // "All apps"
        tiles.set(list);
    }

    private void tileMenu(View anchor, Apps.Entry e) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Move left");
        m.getMenu().add(0, 2, 0, "Move right");
        m.getMenu().add(0, 3, 0, "Remove from home");
        m.getMenu().add(0, 4, 0, "App info");
        m.setOnMenuItemClickListener(item -> {
            List<String> keys = new ArrayList<>();
            for (Apps.Entry f : Apps.favorites(this, prefs)) keys.add(f.key());
            int i = keys.indexOf(e.key());
            switch (item.getItemId()) {
                case 1: if (i > 0) { keys.remove(i); keys.add(i - 1, e.key()); } break;
                case 2: if (i >= 0 && i < keys.size() - 1) { keys.remove(i); keys.add(i + 1, e.key()); } break;
                case 3: keys.remove(e.key()); break;
                case 4: Apps.openAppInfo(this, e.cn.getPackageName()); return true;
            }
            prefs.setFavorites(keys);
            builtStamp = prefs.stamp();
            refreshTiles();
            return true;
        });
        m.show();
    }

    // ---- dock and search actions ---------------------------------------------

    /** Searches in the maps app (Google Maps, Waze or another installed one). */
    private void searchPlace(String q) {
        Uri uri = Uri.parse("geo:0,0?q=" + Uri.encode(q));
        String pkg = NavApps.preferred(this, prefs);
        if (pkg != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
                // This maps app doesn't take searches; let Android pick one that does.
            }
        }
        open(new Intent(Intent.ACTION_VIEW, uri));
    }

    private void openPhone() {
        for (String p : PHONE_APPS) if (launchPackage(p)) return;
        for (Apps.Entry e : Apps.all(this)) {
            String l = e.label.toLowerCase(Locale.ROOT);
            if (l.contains("phone") || l.contains("telepon") || l.contains("bluetooth") || l.equals("bt")) {
                Apps.launch(this, e.cn);
                return;
            }
        }
        open(new Intent(Intent.ACTION_DIAL));
    }

    private void openMusic() {
        if (controller != null) { openPlayer(); return; }
        if (launchPackage("com.spotify.music")) return;
        open(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC));
    }

    /** Always a maps app: the one navigating right now, else the chosen or first installed one. */
    private void openNavigation() {
        if (NavState.active && launchPackage(NavState.pkg)) return;
        String pkg = NavApps.preferred(this, prefs);
        if (pkg != null && launchPackage(pkg)) return;
        Toast.makeText(this, "No maps app installed (Google Maps, Waze, Organic Maps\u2026)", Toast.LENGTH_LONG).show();
    }

    private boolean launchPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return open(i);
    }

    private boolean open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Not available on this unit", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // ---- calendar and weather -------------------------------------------------

    private void updateCalendar() {
        Calendar c = Calendar.getInstance();
        Date now = c.getTime();
        if (calDay == null) { // map layout: date line above the large clock
            calMonth.setText(new SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
            return;
        }
        calMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        calDay.setText(new SimpleDateFormat("EEEE", Locale.getDefault()).format(now));
        calToday.setText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)));
        c.add(Calendar.DAY_OF_MONTH, -1);
        calPrev.setText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)));
        c.add(Calendar.DAY_OF_MONTH, 2);
        calNext.setText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)));
    }

    private void showWeather() {
        if (wTemp == null) return;
        float t = prefs.weatherTemp();
        int code = prefs.weatherCode();
        if (Float.isNaN(t) || code < 0) {
            wCond.setText("–");
            wTemp.setText("--°");
            wCity.setText(locationAllowed() ? "  Waiting for location" : "  Allow GPS in settings");
            wIcon.setText("");
            return;
        }
        wCond.setText(Weather.label(code));
        wTemp.setText(Math.round(t) + "°C");
        String city = prefs.weatherCity();
        wCity.setText(city.isEmpty() ? "" : " \u00b7 " + city);
        wIcon.setText(Weather.emoji(code));
    }

    // ---- navigation card --------------------------------------------------------

    @Override
    public void onNavChanged() {
        boolean show = prefs.showNavCard() && NavState.active;
        // Experimental Maps window: opened once when navigation starts (not on every return
        // home, so Orca Home stays usable), and put behind Orca Home when it ends.
        if (show != navWasActive && mapsWindowOn()) {
            if (show) main.postDelayed(() -> { if (NavState.active && map != null) openMapsWindow(); }, 600);
            else homeToFront();
        }
        navWasActive = show;
        if (navBanner != null) {
            // Map layouts: the search bar steps aside and the guidance goes big over the map.
            topArea.setVisibility(show ? View.GONE : View.VISIBLE);
            navBanner.setVisibility(show ? View.VISIBLE : View.GONE);
            bannerEta.setVisibility(show && !NavState.eta.isEmpty() ? View.VISIBLE : View.GONE);
            if (!show) return;
            hideKeyboard();
            bannerDistance.setText(NavState.distance);
            bannerInstruction.setText(NavState.instruction);
            bannerEta.setText(NavState.eta);
            Drawable a = NavState.arrow(this);
            if (a != null) {
                bannerArrow.clearColorFilter();
                bannerArrow.setImageDrawable(a);
            } else {
                bannerArrow.setImageResource(R.drawable.ic_turn);
                bannerArrow.setColorFilter(0xFFFFFFFF);
            }
            return;
        }
        navCard.setVisibility(show ? View.VISIBLE : View.GONE);
        searchArea.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        if (!show) return;
        navDistance.setText(NavState.distance);
        navInstruction.setText(NavState.instruction);
        navEta.setText(NavState.eta);
        Drawable arrow = NavState.arrow(this);
        if (arrow != null) {
            navArrow.clearColorFilter();
            navArrow.setImageDrawable(arrow);
        } else {
            navArrow.setImageResource(R.drawable.ic_turn);
            navArrow.setColorFilter(ui.onAccent);
        }
    }

    // ---- speed gauge (GPS only while visible) ------------------------------------

    private final LocationListener speedListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            if (map != null) {
                map.setLocation(l);
                if (mapHint.length() > 0) mapHint.setText("");
            }
            if (speedBubble != null && l.hasSpeed()) {
                int kmh = Math.round(l.getSpeed() * 3.6f);
                speedBubble.setText((kmh < 2 ? 0 : kmh) + "\nkm/h");
            }
            if (gauge != null) {
                if (l.hasSpeed()) {
                    int kmh = Math.round(l.getSpeed() * 3.6f);
                    gauge.setSpeed(kmh < 2 ? 0 : kmh);
                }
                gauge.setHint("GPS");
            }
            if (prefs.showWeather()) Weather.refreshIfStale(MainActivity.this, prefs, MainActivity.this::showWeather);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { showGpsState(true); }
        @Override public void onProviderDisabled(String p) { showGpsState(false); }
    };

    // ---- keyboard ----------------------------------------------------------------

    private void hideKeyboard() {
        if (searchField == null) return;
        searchField.clearFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

    /** A tap anywhere outside the search bar closes the keyboard. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && searchField != null && searchField.hasFocus()) {
            int[] at = new int[2];
            searchField.getLocationOnScreen(at);
            float x = ev.getRawX(), y = ev.getRawY();
            if (x < at[0] || x > at[0] + searchField.getWidth() || y < at[1] || y > at[1] + searchField.getHeight()) hideKeyboard();
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---- navigation notifications ------------------------------------------------

    /**
     * NWD firmware can keep Android from connecting the notification reader at boot
     * (before Orca Home has been opened), and then the nav card never shows. Ask again.
     */
    private void rebindListener() {
        if (MediaListener.connected) return;
        ComponentName me = new ComponentName(this, MediaListener.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationListenerAccessGranted(me)) return;
        try { NotificationListenerService.requestRebind(me); } catch (RuntimeException ignored) { }
    }

    private boolean locationAllowed() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askLocation() {
        if (!locationAllowed()) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        else if (!location.isProviderEnabled(LocationManager.GPS_PROVIDER)) open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    private void startSpeed() {
        stopSpeed();
        if (gauge == null && map == null) return;
        if (!locationAllowed()) {
            if (gauge != null) { gauge.setSpeed(-1); gauge.setHint("Tap to allow GPS"); }
            if (mapHint != null) mapHint.setText("Tap the map to allow GPS");
            return;
        }
        showGpsState(location.isProviderEnabled(LocationManager.GPS_PROVIDER));
        // Registered even while GPS is switched off, so onProviderEnabled tells us when it comes back.
        try {
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, speedListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            if (gauge != null) gauge.setHint("GPS unavailable");
        }
    }

    private void showGpsState(boolean on) {
        if (gauge != null) {
            if (!on) gauge.setSpeed(-1);
            gauge.setHint(on ? "Waiting for GPS" : "GPS is off");
        }
        if (mapHint != null && !map.hasFix()) mapHint.setText(on ? "Waiting for GPS\u2026" : "GPS is off \u2013 tap to turn it on");
    }

    private void stopSpeed() {
        location.removeUpdates(speedListener);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (map != null) showLastKnownLocation();
        startSpeed();
        showWeather();
    }

    // ---- music card -----------------------------------------------------------------

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pickController;

    private final MediaController.Callback trackCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { meta = m; showTrack(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { playback = s; showState(); }
        @Override public void onSessionDestroyed() { pickController(null); }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (visible && isPlaying()) main.postDelayed(this, 1000);
        }
    };

    private void startMusic() {
        ComponentName me = new ComponentName(this, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, me);
            sessionsListening = true;
            pickController(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            sessionsListening = false;
            setController(null);
        }
    }

    private void stopMusic() {
        main.removeCallbacks(progressTick);
        if (sessionsListening) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        sessionsListening = false;
        if (controller != null) controller.unregisterCallback(trackCallback);
        controller = null;
        meta = shownMeta = null;
        playback = null;
    }

    private void pickController(List<MediaController> list) {
        if (list == null) {
            try {
                list = sessions.getActiveSessions(new ComponentName(this, MediaListener.class));
            } catch (SecurityException e) {
                list = null;
            }
        }
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        setController(pick);
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(trackCallback);
        controller = c;
        meta = c != null ? c.getMetadata() : null;
        playback = c != null ? c.getPlaybackState() : null;
        shownMeta = null;
        if (controller != null) controller.registerCallback(trackCallback);
        showTrack();
    }

    private boolean isPlaying() {
        return playback != null && playback.getState() == PlaybackState.STATE_PLAYING;
    }

    private void showTrack() {
        if (musicTitle == null) return;
        MediaController c = controller;
        MediaMetadata m = meta;
        if (c == null || m == null) {
            shownMeta = null;
            main.removeCallbacks(progressTick);
            musicSourceIcon.setImageDrawable(null);
            musicSource.setText(sessionsListening ? "NOTHING PLAYING" : "MUSIC CARD IS OFF – ALLOW IN SETTINGS");
            musicTitle.setText(c != null ? appLabel(c.getPackageName()) : "Start Spotify, YouTube or radio");
            musicArtist.setText("");
            musicArt.setImageResource(R.drawable.ic_music);
            musicArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            musicArt.setColorFilter(ui.sub);
            playPause.setImageResource(R.drawable.ic_play);
            progressFill.setScaleX(0);
            setIfChanged(musicElapsed, "");
            setIfChanged(musicDuration, "");
            return;
        }
        if (m != shownMeta) { // a new song (or a new player): texts, art and app icon once
            shownMeta = m;
            try { musicSourceIcon.setImageDrawable(getPackageManager().getApplicationIcon(c.getPackageName())); }
            catch (PackageManager.NameNotFoundException e) { musicSourceIcon.setImageDrawable(null); }
            String app = appLabel(c.getPackageName());
            musicSource.setText(("PLAYING FROM " + app).toUpperCase(Locale.getDefault()));
            String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist)) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            musicTitle.setText(TextUtils.isEmpty(title) ? app : title);
            musicArtist.setText(TextUtils.isEmpty(artist) ? "" : artist);
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art != null) {
                musicArt.clearColorFilter();
                musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
                musicArt.setImageBitmap(art);
            } else {
                musicArt.setImageResource(R.drawable.ic_music);
                musicArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                musicArt.setColorFilter(ui.sub);
            }
        }
        showState();
    }

    /** Play/pause button and progress; the 1-second tick runs only while playing and visible. */
    private void showState() {
        if (musicTitle == null) return;
        main.removeCallbacks(progressTick);
        if (controller == null || meta == null) return;
        playPause.setImageResource(isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        updateProgress();
        if (visible && isPlaying()) main.postDelayed(progressTick, 1000);
    }

    private void updateProgress() {
        MediaMetadata m = meta;
        if (controller == null || m == null) return;
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        PlaybackState s = playback;
        long pos = s == null ? 0 : s.getPosition();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        if (dur > 0) {
            pos = Math.max(0, Math.min(pos, dur));
            progressFill.setScaleX(pos / (float) dur);
            setIfChanged(musicElapsed, time(pos));
            setIfChanged(musicDuration, time(dur));
        } else {
            progressFill.setScaleX(0);
            setIfChanged(musicElapsed, "");
            setIfChanged(musicDuration, "");
        }
    }

    /** setText re-measures the whole screen for a wrap-content view, even for the same text. */
    private static void setIfChanged(TextView v, CharSequence s) {
        if (!TextUtils.equals(v.getText(), s)) v.setText(s);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void togglePlay() {
        if (controller == null) { openMusic(); return; }
        if (isPlaying()) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private void openPlayer() {
        if (!sessionsListening) {
            open(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            return;
        }
        if (controller == null) return;
        PendingIntent pi = controller.getSessionActivity();
        if (pi != null) {
            try { pi.send(); return; } catch (PendingIntent.CanceledException ignored) { }
        }
        launchPackage(controller.getPackageName());
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** As wide as it is tall (the square map). */
    private static final class SquareFrame extends FrameLayout {
        SquareFrame(Context c) { super(c); }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int side = MeasureSpec.getSize(heightSpec);
            int max = MeasureSpec.getSize(widthSpec);
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && max > 0) side = Math.min(side, max);
            int exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
            super.onMeasure(exact, exact);
        }
    }
}
