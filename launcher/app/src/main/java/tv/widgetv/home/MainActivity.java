package tv.widgetv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.MetricAffectingSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen. The activity owns the D-pad completely: nothing is focusable, focus
 * is a (zone, index) pair, and one gold ring (GlassHighlight) springs to whatever that points at.
 * Zones top to bottom: pills (TOP), prayer timeline (or, with prayer_enabled=false, the clock header),
 * dock, grid; plus the Inputs dropdown and the settings panel as modal zones. Clocks and prayer times
 * update on ACTION_TIME_TICK. The hadith rotates on its own timer while home is in front. Every user
 * choice comes from res/values/config.xml (Config).
 */
public class MainActivity extends Activity {
    private static final String TAG = Config.TAG;
    private static final int COLS = 4;
    /** Debug broadcast action: "<applicationId>.DEBUG_SKY". */
    private String debugAction() {
        return getPackageName() + ".DEBUG_SKY";
    }

    private enum Zone { TOP, TIMELINE, DOCK, GRID, MENU, PANEL }

    private ScrollView page;
    private FrameLayout content;
    private SkyView sky;
    private FireBackdrop fire;
    private PrayerTimeline timeline;
    private GlassHighlight glass;
    private TextView clock, awayClock, date, ssid;
    private ImageView[] pills; // the three round buttons: Inputs, Wi-Fi, Settings
    private Typeface ui;
    private LinearLayout menu, panel;
    private RecyclerView dockList, gridList;
    private TileAdapter dockAdapter, gridAdapter;
    private List<TvInputs.Entry> menuEntries = new ArrayList<>();

    private Zone zone = Zone.DOCK;
    private int pillIdx, dockIdx, gridIdx, menuIdx, panelIdx;
    private String pendingPkg;        // focus to restore once apps are loaded
    private boolean resumed;
    private boolean needScroll = true;
    private int firstScreenH;
    private String lastFocusLog = "";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppLoader loader;
    private List<String> lastSignature;   // io thread only
    private static final int DOCK_SLOTS = 4;
    private List<String> dockPackages, hiddenPackages;
    /** Set while OK is held, so the release does not also open the app. */
    private boolean centerHeld;
    private SharedPreferences prefs;
    private Weather weather;
    private PrayerDay day;
    /** Debug builds only: a fixed "now" for the timeline and sky (adb fake_time), or -1. */
    private int fakeMinutes = -1; // debug fake_time as minutes of the day, or -1
    /** Debug builds only: a fixed local date for the thought of the day (adb fake_date), or null. */
    private LocalDate fakeDate;
    private Thoughts thoughts;
    private View hereWeather, thought;
    private ImageView awayFlag;
    private TextView hereTemp, awayTemp, awayRange, thoughtTag, thoughtText, thoughtSource, adhanBlob;
    private LocalDate shownThoughtDate;
    /** Steps past today's stable hadith. The home screen advances this while it is in front. */
    private int thoughtSlot;
    private static final long THOUGHT_EVERY_MS = 60_000L;
    private Config cfg;
    private View header, awayRow;
    /** The panel's rows (ROW_* ids): all nine with prayer times on, only font and text size without. */
    private int[] panelRows;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiLang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        page = findViewById(R.id.page);
        content = findViewById(R.id.content);
        sky = findViewById(R.id.sky);
        TextureView fireTexture = findViewById(R.id.fire);
        SurfaceView fireSurface = null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // A SurfaceView in the layout punches a hole through the picture on newer TVs.
            fireSurface = new SurfaceView(this);
            FrameLayout root = findViewById(R.id.root);
            root.addView(fireSurface, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        fire = new FireBackdrop(this, fireTexture, fireSurface, findViewById(R.id.fire_scrim),
                findViewById(R.id.fire_dim), findViewById(R.id.root));
        timeline = findViewById(R.id.timeline);
        glass = findViewById(R.id.glass);
        clock = findViewById(R.id.clock);
        awayClock = findViewById(R.id.away_clock);
        ssid = findViewById(R.id.ssid);
        hereWeather = findViewById(R.id.here_weather);
        hereTemp = findViewById(R.id.here_temp);
        awayFlag = findViewById(R.id.away_flag);
        awayTemp = findViewById(R.id.away_temp);
        awayRange = findViewById(R.id.away_range);
        thought = findViewById(R.id.thought);
        thoughtTag = findViewById(R.id.thought_tag);
        thoughtText = findViewById(R.id.thought_text);
        thoughtSource = findViewById(R.id.thought_source);
        thoughts = new Thoughts(this);
        cfg = Config.get(this);
        header = findViewById(R.id.header);
        awayRow = findViewById(R.id.away_row);
        panelRows = cfg.prayerEnabled ? new int[]{ROW_FIRE, ROW_ADHAN, ROW_ASR, ROW_METHOD, ROW_OFFSET, ROW_OFFSET + 1, ROW_OFFSET + 2,
                ROW_OFFSET + 3, ROW_OFFSET + 4, ROW_FONT, ROW_TEXT_SIZE} : new int[]{ROW_FIRE, ROW_FONT, ROW_TEXT_SIZE};
        timeline.setVisibility(cfg.prayerEnabled ? View.VISIBLE : View.GONE);
        // Second clock: hidden entirely when off; the flag is hidden when its drawable name is unknown.
        awayRow.setVisibility(cfg.secondEnabled ? View.VISIBLE : View.GONE);
        if (cfg.secondFlag != 0) awayFlag.setImageResource(cfg.secondFlag);
        else awayFlag.setVisibility(View.GONE);
        awayClock.setContentDescription(cfg.secondLabel);
        boolean ar = "ar".equals(Locale.getDefault().getLanguage());
        ui = Fonts.get(this, ar ? R.font.noto_naskh : R.font.alexandria);
        date = findViewById(R.id.date);
        date.setTypeface(ui);
        ssid.setTypeface(ui);
        awayRange.setTypeface(ui);
        thoughtTag.setTypeface(ui);
        thoughtText.setTypeface(ar ? ui : Fonts.get(this, R.font.cormorant_garamond_italic));
        if (ar) {
            thoughtTag.setLetterSpacing(0f);
            thoughtTag.setAllCaps(false);
        }
        thoughtSource.setTypeface(ui);
        adhanBlob = new TextView(this);
        adhanBlob.setVisibility(View.GONE);
        adhanBlob.setTypeface(ui);
        adhanBlob.setTextColor(Lapis.LAPIS_900);
        adhanBlob.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        adhanBlob.setBackgroundResource(R.drawable.bg_blob);
        adhanBlob.setPadding(dp(22), dp(8), dp(22), dp(8));
        FrameLayout.LayoutParams blobLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blobLp.topMargin = dp(28);
        ((ViewGroup) getWindow().getDecorView()).addView(adhanBlob, blobLp);
        menu = findViewById(R.id.menu);
        panel = findViewById(R.id.panel);
        pills = new ImageView[]{findViewById(R.id.btn_inputs), findViewById(R.id.btn_wifi),
                findViewById(R.id.btn_settings)};
        dockPackages = Arrays.asList(getResources().getStringArray(R.array.dock_packages));
        hiddenPackages = Arrays.asList(getResources().getStringArray(R.array.hidden_packages));
        weather = new Weather(this);
        applyClockFont();
        applyTextSize();

        Rect window = windowBounds();
        firstScreenH = window.height();
        findViewById(R.id.first_screen).getLayoutParams().height = firstScreenH;
        // Dock band and grid share the same 4 columns: width minus 2 x (40 margin + 16 band padding).
        int cardW = (window.width() - dp(112)) / COLS - dp(24);
        int cardH = cardW * 9 / 16;
        loader = new AppLoader(getApplicationContext(), cardW, cardH);
        // v2 slim dock: tiles at ~70% of the grid tile height, still 16:9 banners, centred in their columns.
        int dockH = Math.round(cardH * 0.7f);
        dockAdapter = new TileAdapter(dockH * 16 / 9, dockH);
        gridAdapter = new TileAdapter(-1, cardH);
        setUpList(dockList = findViewById(R.id.dock), dockAdapter);
        setUpList(gridList = findViewById(R.id.grid), gridAdapter);

        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        try {
            zone = Zone.valueOf(prefs.getString("zone", Zone.DOCK.name()));
        } catch (IllegalArgumentException old) {
            zone = Zone.DOCK;
        }
        if (zone == Zone.MENU) zone = Zone.TOP;
        if (zone == Zone.PANEL) zone = Zone.TIMELINE;
        pillIdx = prefs.getInt("pill", 0);
        pendingPkg = prefs.getString("pkg", null);

        content.getViewTreeObserver().addOnGlobalLayoutListener(this::onLayoutChanged);
        page.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float dim = y / (firstScreenH * 0.6f);
            sky.setDim(dim);
            fire.setDim(dim);
        });

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        register(packageReceiver, pkgFilter, false);
        if (BuildConfig.DEBUG) register(debugReceiver, new IntentFilter(debugAction()), true);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        // The Handler overload is Android 8. Android 7 takes the callback on the main thread.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cm.registerDefaultNetworkCallback(networkCallback, main);
        else cm.registerDefaultNetworkCallback(networkCallback);
    }

    private void setUpList(RecyclerView list, TileAdapter adapter) {
        list.setLayoutManager(new GridLayoutManager(this, COLS));
        list.setItemAnimator(null);
        list.setNestedScrollingEnabled(false);
        list.setFocusable(false);
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, timeFilter, false);
        AdhanPlayer.setCue(adhanCue);
        if (AdhanPlayer.activePrayer() >= 0) showAdhanBlob(AdhanPlayer.activePrayer(), AdhanPlayer.activeMode());
        applyFire();
        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, main);
        updateClock(); // also the weather stale check
        scheduleThoughtRotation();
        AdhanAlarm.schedule(this);
        loadApps(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(rotateThought);
        main.removeCallbacks(hideAdhanBlob);
        AdhanPlayer.setCue(null);
        adhanBlob.setVisibility(View.GONE);
        fire.stop();
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        unregisterReceiver(timeReceiver);
        if (zone == Zone.MENU) closeMenu();
        if (zone == Zone.PANEL) closePanel();
        String pkg = focusedPkg();
        prefs.edit().putString("zone", zone.name()).putInt("pill", pillIdx).putString("pkg", pkg).apply();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Returning from the input picker does not always resume the activity. The video buffer
        // is gone; the crackle is not. Attach the picture again.
        if (hasFocus && resumed) fire.rebind();
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (resumed) fire.rebind();
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: back to the first dock tile. Returning from an app keeps focus.
        // Android pauses the activity before onNewIntent, so "already home" = our window still has focus.
        boolean atHome = resumed || hasWindowFocus();
        if (BuildConfig.DEBUG) Log.d(TAG, "onNewIntent: resumed=" + resumed + " windowFocus=" + hasWindowFocus());
        if (atHome) {
            pendingPkg = null; // a still-loading app list must not restore the old focus over this
            if (dockAdapter.getItemCount() == 0 && zone != Zone.MENU && zone != Zone.PANEL) {
                zone = Zone.DOCK;
                dockIdx = 0; // applied by applyApps; with tiles present focus() below un-highlights the old one first
            }
        }
        if (atHome && dockAdapter.getItemCount() > 0) {
            if (zone == Zone.MENU) closeMenu();
            if (zone == Zone.PANEL) closePanel();
            focus(Zone.DOCK, 0, true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(rotateThought);
        main.removeCallbacks(hideAdhanBlob);
        AdhanPlayer.setCue(null);
        fire.stop();
        unregisterReceiver(packageReceiver);
        if (BuildConfig.DEBUG) unregisterReceiver(debugReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        io.shutdownNow();
        weather.shutdown();
    }

    // ---------------------------------------------------------------- apps

    private void loadApps(boolean force) {
        io.execute(() -> {
            Map<String, AppLoader.Candidate> found = loader.query();
            List<String> sig = new ArrayList<>();
            for (AppLoader.Candidate c : found.values()) sig.add(c.key());
            if (!force && sig.equals(lastSignature)) return;
            lastSignature = sig;

            List<AppEntry> dock = new ArrayList<>();
            for (String pkg : dockOrder()) {
                if (dock.size() >= DOCK_SLOTS) break;
                AppLoader.Candidate c = found.remove(pkg);
                if (c != null) dock.add(loader.entry(c));
            }
            for (String pkg : hiddenPackages) found.remove(pkg);
            List<AppEntry> grid = new ArrayList<>();
            for (AppLoader.Candidate c : found.values()) grid.add(loader.entry(c));
            grid.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            main.post(() -> applyApps(dock, grid));
        });
    }

    private void applyApps(List<AppEntry> dock, List<AppEntry> grid) {
        String keep = pendingPkg != null ? pendingPkg : focusedPkg();
        pendingPkg = null;
        dockAdapter.setItems(dock);
        gridAdapter.setItems(grid);
        int i = indexOf(dock, keep);
        if (i >= 0) dockIdx = i;
        int g = indexOf(grid, keep);
        if (g >= 0) gridIdx = g;
        dockIdx = clamp(dockIdx, dock.size());
        gridIdx = clamp(gridIdx, grid.size());
        if (zone == Zone.GRID && grid.isEmpty()) zone = Zone.DOCK;
        if (zone == Zone.DOCK && dock.isEmpty()) zone = Zone.TIMELINE;
        dockAdapter.focused = zone == Zone.DOCK ? dockIdx : -1;
        gridAdapter.focused = zone == Zone.GRID ? gridIdx : -1;
        if (zone == Zone.TOP) setLook(true);
        needScroll = true; // scroll + ring are placed by onLayoutChanged once the tiles exist
        Log.i(TAG, "apps: dock=" + dock.size() + " grid=" + grid.size());
    }

    private static int indexOf(List<AppEntry> list, String pkg) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).pkg().equals(pkg)) return i;
        return -1;
    }

    private String focusedPkg() {
        if (zone == Zone.DOCK && dockIdx < dockAdapter.items.size()) return dockAdapter.items.get(dockIdx).pkg();
        if (zone == Zone.GRID && gridIdx < gridAdapter.items.size()) return gridAdapter.items.get(gridIdx).pkg();
        return null;
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            String pkg = data == null ? null : data.getSchemeSpecificPart();
            io.execute(() -> {
                if (pkg != null) loader.forget(pkg);
            });
            loadApps(true);
        }
    };

    // ---------------------------------------------------------------- D-pad

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(code);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    // A held OK rearranges the featured row. A tap still opens the app.
                    // The first repeat is the long-press. A tap is a single down with repeat 0.
                    if ((ev.getRepeatCount() >= 1
                            || (ev.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) && !centerHeld) {
                        centerHeld = true;
                        arrangeFocused();
                    }
                } else {
                    if (!centerHeld && !ev.isCanceled()) select();
                    centerHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                // Home never goes "back"; Back only closes the dropdown or the panel.
                if (!down && zone == Zone.MENU) closeMenu();
                if (!down && zone == Zone.PANEL) closePanel();
                return true;
            default:
                return super.dispatchKeyEvent(ev);
        }
    }

    private void move(int code) {
        int dockN = dockAdapter.getItemCount(), gridN = gridAdapter.getItemCount();
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP, dn = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        // Arabic lays the first tile on the right. A press on the left of the remote should
        // still move the highlight toward the left of the screen.
        if (zone != Zone.PANEL && zone != Zone.MENU
                && getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            boolean towardScreenLeft = left;
            left = right;
            right = towardScreenLeft;
        }
        switch (zone) {
            case TOP:
                if (left && pillIdx > 0) focus(Zone.TOP, pillIdx - 1, true);
                else if (right && pillIdx < pills.length - 1) focus(Zone.TOP, pillIdx + 1, true);
                else if (dn) focus(Zone.TIMELINE, 0, true);
                break;
            case TIMELINE:
                if (up) focus(Zone.TOP, pillIdx, true);
                else if (dn && dockN > 0) focus(Zone.DOCK, clamp(dockIdx, dockN), true);
                else if (dn && gridN > 0) focus(Zone.GRID, clamp(gridIdx, gridN), true);
                break;
            case DOCK:
                if (left && dockIdx > 0) focus(Zone.DOCK, dockIdx - 1, true);
                else if (right && dockIdx < dockN - 1) focus(Zone.DOCK, dockIdx + 1, true);
                else if (up) focus(Zone.TIMELINE, 0, true);
                else if (dn && gridN > 0) focus(Zone.GRID, Math.min(dockIdx, gridN - 1), true);
                break;
            case GRID: {
                int col = gridIdx % COLS, row = gridIdx / COLS;
                if (left && col > 0) focus(Zone.GRID, gridIdx - 1, true);
                else if (right && col < COLS - 1 && gridIdx + 1 < gridN) focus(Zone.GRID, gridIdx + 1, true);
                else if (up && row > 0) focus(Zone.GRID, gridIdx - COLS, true);
                else if (up && dockN > 0) focus(Zone.DOCK, clamp(dockIdx, dockN), true); // last focused dock tile
                else if (up) focus(Zone.TIMELINE, 0, true);
                else if (dn && gridIdx + COLS < gridN) focus(Zone.GRID, gridIdx + COLS, true);
                else if (dn && (row + 1) * COLS < gridN) focus(Zone.GRID, gridN - 1, true); // short last row
                break;
            }
            case MENU:
                if (up && menuIdx > 0) focus(Zone.MENU, menuIdx - 1, true);
                else if (dn && menuIdx < menuEntries.size() - 1) focus(Zone.MENU, menuIdx + 1, true);
                break;
            case PANEL:
                if (up && panelIdx > 0) focus(Zone.PANEL, panelIdx - 1, true);
                else if (dn && panelIdx < panelRows.length - 1) focus(Zone.PANEL, panelIdx + 1, true);
                else if (left || right) changeSetting(panelRows[panelIdx], right ? 1 : -1);
                break;
        }
    }

    /** Hold OK on a grid app to put it first. Hold OK on a featured app to move it one step forward. */
    private void arrangeFocused() {
        if (zone != Zone.DOCK && zone != Zone.GRID) return;
        String pkg = focusedPkg();
        if (pkg == null) return;
        String label = zone == Zone.DOCK
                ? dockAdapter.items.get(dockIdx).label
                : gridAdapter.items.get(gridIdx).label;
        List<String> order = new ArrayList<>();
        for (AppEntry entry : dockAdapter.items) order.add(entry.pkg());
        int fromGrid = zone == Zone.GRID ? 1 : 0;
        int at = order.indexOf(pkg);
        if (fromGrid == 1) {
            order.remove(pkg);
            order.add(0, pkg);
            while (order.size() > DOCK_SLOTS) order.remove(order.size() - 1);
            Toast.makeText(this, getString(R.string.dock_now_first, label), Toast.LENGTH_SHORT).show();
        } else if (at <= 0) {
            return;
        } else {
            order.remove(at);
            order.add(at - 1, pkg);
            Toast.makeText(this, getString(R.string.dock_moved_up, label), Toast.LENGTH_SHORT).show();
        }
        getSharedPreferences("dock", MODE_PRIVATE).edit()
                .putString("order", TextUtils.join(",", order)).apply();
        pendingPkg = pkg;
        zone = Zone.DOCK;
        loadApps(true);
    }

    /** Saved featured order, or the config list until the first hold-OK. */
    private List<String> dockOrder() {
        String saved = getSharedPreferences("dock", MODE_PRIVATE).getString("order", "");
        if (saved == null || saved.isEmpty()) return dockPackages;
        List<String> order = new ArrayList<>();
        for (String pkg : saved.split(",")) if (!pkg.isEmpty()) order.add(pkg);
        return order.isEmpty() ? dockPackages : order;
    }

    private void select() {
        switch (zone) {
            case TOP:
                if (pillIdx == 0) openMenu();
                else if (pillIdx == 1) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else start(new Intent(Settings.ACTION_SETTINGS));
                break;
            case TIMELINE:
                openPanel();
                break;
            case DOCK:
                if (dockIdx < dockAdapter.items.size()) start(dockAdapter.items.get(dockIdx).launchIntent());
                break;
            case GRID:
                if (gridIdx < gridAdapter.items.size()) start(gridAdapter.items.get(gridIdx).launchIntent());
                break;
            case MENU:
                Intent intent = menuEntries.get(menuIdx).intent;
                closeMenu();
                start(intent);
                break;
            case PANEL:
                closePanel();
                break;
        }
    }

    private void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot start " + intent, e);
        }
    }

    // ---------------------------------------------------------------- focus rendering

    private void focus(Zone z, int idx, boolean animate) {
        setLook(false);
        zone = z;
        switch (z) {
            case TOP: pillIdx = idx; break;
            case DOCK: dockIdx = idx; break;
            case GRID: gridIdx = idx; break;
            case MENU: menuIdx = idx; break;
            case PANEL: panelIdx = idx; break;
            default: break;
        }
        dockAdapter.focused = z == Zone.DOCK ? dockIdx : -1;
        gridAdapter.focused = z == Zone.GRID ? gridIdx : -1;
        setLook(true);
        scrollToZone(animate);
        updateGlass(animate);
    }

    private View focusedView() {
        switch (zone) {
            case TOP: return pills[pillIdx];
            case TIMELINE: return cfg.prayerEnabled ? timeline : header;
            case DOCK: return dockList.getLayoutManager().findViewByPosition(dockIdx);
            case GRID: return gridList.getLayoutManager().findViewByPosition(gridIdx);
            case MENU: return menu.getChildAt(menuIdx);
            default: return panel.getChildAt(panelIdx + 1); // child 0 is the title
        }
    }

    private void setLook(boolean on) {
        View v = focusedView();
        if (v == null) return;
        switch (zone) {
            case DOCK:
            case GRID:
                TileAdapter.setLook(v, on);
                break;
            case TOP: {
                ImageView b = (ImageView) v;
                b.setBackgroundResource(on ? R.drawable.bg_btn_focus : R.drawable.bg_btn);
                b.setImageTintList(ColorStateList.valueOf(on ? Lapis.LAPIS_900 : Lapis.PLASTER_100));
                ssid.setVisibility(on && pillIdx == 1 ? View.VISIBLE : View.GONE); // SSID only while Wi-Fi is focused
                break;
            }
            case MENU:
            case PANEL:
                rowLook(v, on);
                break;
            default:
                break;
        }
    }

    /** Dropdown / panel row: gold fill with lapis text when focused. */
    private static void rowLook(View row, boolean on) {
        row.setBackgroundResource(on ? R.drawable.bg_row_focus : 0);
        if (row instanceof TextView) {
            ((TextView) row).setTextColor(on ? Lapis.LAPIS_900 : Lapis.PLASTER_100);
        } else if (row instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) row;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = (TextView) g.getChildAt(i);
                t.setTextColor(on ? Lapis.LAPIS_900 : (i == 0 ? Lapis.PLASTER_100 : Lapis.GOLD_400));
            }
        }
    }

    /** Re-place the ring after any layout pass (data loaded, SSID text width changed, menu shown). */
    private void onLayoutChanged() {
        if (needScroll && focusedView() != null) {
            needScroll = false;
            if (zone == Zone.TOP) setLook(true);
            scrollToZone(false);
        }
        updateGlass(true);
    }

    private void updateGlass(boolean animate) {
        View v = focusedView();
        if (v == null || v.getWidth() == 0) return;
        RectF r;
        float radius;
        if (zone == Zone.DOCK || zone == Zone.GRID) {
            r = rectInContent(v.findViewById(R.id.card));
            float s = TileAdapter.FOCUS_SCALE - 1f;
            r.inset(-(r.width() * s / 2 + dp(3)), -(r.height() * s / 2 + dp(3)));
            radius = dp(12) * TileAdapter.FOCUS_SCALE + dp(3);
        } else if (zone == Zone.TIMELINE) {
            r = rectInContent(v);
            r.inset(-dp(14), -dp(8));
            radius = dp(16);
        } else {
            r = rectInContent(v);
            radius = r.height() / 2;
        }
        boolean ring = zone == Zone.TIMELINE || zone == Zone.DOCK || zone == Zone.GRID;
        String focusLog = "focus: zone=" + zone + " ring=" + ring + " rect=" + r.toShortString();
        if (BuildConfig.DEBUG && !focusLog.equals(lastFocusLog)) Log.d(TAG, lastFocusLog = focusLog);
        glass.setRingVisible(ring);
        glass.moveTo(r, radius, animate);
    }

    /** Dock/timeline/top bar keep the first screen in view; grid rows sit 80dp below the top edge. */
    private void scrollToZone(boolean smooth) {
        int y = 0;
        if (zone == Zone.GRID) {
            View v = focusedView();
            if (v == null) return;
            y = Math.max(0, (int) rectInContent(v).top - dp(80));
        }
        if (smooth) page.smoothScrollTo(0, y);
        else page.scrollTo(0, y);
    }

    private RectF rectInContent(View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        content.offsetDescendantRectToMyCoords(v, r);
        return new RectF(r);
    }

    // ---------------------------------------------------------------- Inputs dropdown

    private void openMenu() {
        menuEntries = TvInputs.load(this);
        if (menuEntries.isEmpty()) return;
        menu.removeAllViews();
        for (TvInputs.Entry e : menuEntries) {
            TextView row = rowText(e.label, 16, Lapis.PLASTER_100);
            row.setPadding(dp(16), 0, dp(16), 0);
            menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        }
        // Right-aligned under the button row (the buttons sit at the screen's right edge).
        RectF pill = rectInContent(pills[0]);
        RectF last = rectInContent(pills[pills.length - 1]);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.leftMargin = (int) last.right - lp.width;
        lp.topMargin = (int) pill.bottom + dp(12);
        menu.setLayoutParams(lp);
        menu.setAlpha(0f);
        menu.setVisibility(View.VISIBLE);
        menu.animate().alpha(1f).setDuration(160).start();
        focus(Zone.MENU, 0, true);
    }

    private void closeMenu() {
        menu.setVisibility(View.GONE);
        menu.removeAllViews();
        zone = Zone.TOP; // the rows are gone; focus() below has nothing to un-highlight
        focus(Zone.TOP, 0, true);
    }

    private TextView rowText(CharSequence text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(ui);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        return t;
    }

    // ---------------------------------------------------------------- Prayer settings panel

    /** Row ids: Asr school, method, the five offsets (Fajr, Dhuhr, Asr, Maghrib, Isha), clock font, text size, adhan. */
    private static final int ROW_ASR = 0, ROW_METHOD = 1, ROW_OFFSET = 2, ROW_FONT = 7, ROW_TEXT_SIZE = 8, ROW_ADHAN = 9, ROW_FIRE = 10;
    private static final int PANEL_ROW_DP = 26;

    private void openPanel() {
        panel.removeAllViews();
        LinearLayout title = new LinearLayout(this);
        String titleText = cfg.prayerEnabled
                ? getString(R.string.prayer_settings) + (cfg.cityLabel.isEmpty() ? "" : "  ·  " + cfg.cityLabel)
                : getString(R.string.display_settings);
        title.addView(rowText(titleText, 15, Lapis.GOLD_400),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        title.addView(rowText(getString(R.string.panel_hint), 11, Lapis.MIST_400),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        title.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        for (int i = 0; i < panelRows.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(dp(14), 0, dp(14), 0);
            row.addView(rowText("", 14, Lapis.PLASTER_100), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(rowText("", 14, Lapis.GOLD_400),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PANEL_ROW_DP)));
        }
        renderPanel();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int panelH = dp(30 + PANEL_ROW_DP * panelRows.length + 16);
        if (cfg.prayerEnabled) {
            RectF tl = rectInContent(timeline);
            lp.leftMargin = (int) (tl.centerX() - dp(210));
            // Just under the timeline's prayer times (they stay visible), never past the first screen's bottom.
            lp.topMargin = (int) Math.min(tl.top + dp(52), firstScreenH - dp(8) - panelH);
        } else {
            RectF hd = rectInContent(header); // under the clock header it was opened from
            lp.leftMargin = (int) hd.left;
            lp.topMargin = (int) Math.min(hd.bottom + dp(16), firstScreenH - dp(8) - panelH);
        }
        panel.setLayoutParams(lp);
        panel.setAlpha(0f);
        panel.setVisibility(View.VISIBLE);
        panel.animate().alpha(1f).setDuration(160).start();
        focus(Zone.PANEL, 0, true);
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        panel.removeAllViews();
        zone = Zone.TIMELINE;
        focus(Zone.TIMELINE, 0, true);
    }

    private void renderPanel() {
        if (panel.getChildCount() < panelRows.length + 1) return;
        for (int k = 0; k < panelRows.length; k++) {
            ViewGroup row = (ViewGroup) panel.getChildAt(k + 1);
            int i = panelRows[k];
            String label, value;
            if (i == ROW_ASR) {
                label = getString(R.string.asr_school);
                value = getString(PrayerPrefs.hanafi(this) ? R.string.hanafi : R.string.shafi);
            } else if (i == ROW_METHOD) {
                label = getString(R.string.method);
                value = getResources().getStringArray(R.array.method_names)[PrayerPrefs.methodIndex(this)];
            } else if (i == ROW_FONT) {
                label = getString(R.string.clock_font);
                value = getResources().getStringArray(R.array.clock_fonts)[PrayerPrefs.clockFont(this)];
            } else if (i == ROW_TEXT_SIZE) {
                label = getString(R.string.text_size);
                value = getResources().getStringArray(R.array.text_sizes)[PrayerPrefs.textSize(this)];
            } else if (i == ROW_ADHAN) {
                label = getString(R.string.adhan);
                value = getResources().getStringArray(R.array.adhan_modes)[PrayerPrefs.adhanMode(this)];
            } else if (i == ROW_FIRE) {
                label = getString(R.string.fire);
                value = getString(PrayerPrefs.fire(this) ? R.string.on : R.string.off);
            } else {
                int prayer = PrayerPrefs.OFFSET_PRAYERS[i - ROW_OFFSET];
                int off = PrayerPrefs.offset(this, prayer);
                String sign = (off > 0 ? "+" : off < 0 ? "−" : "") + Math.abs(off);
                label = getString(R.string.prayer_offset, getResources().getStringArray(R.array.prayer_names)[prayer]);
                value = getString(R.string.offset_value, sign, clockText(day.times[prayer]));
            }
            ((TextView) row.getChildAt(0)).setText(label);
            ((TextView) row.getChildAt(1)).setText(getString(R.string.setting_value, value));
            rowLook(row, zone == Zone.PANEL && k == panelIdx);
        }
    }

    private void changeSetting(int row, int dir) {
        if (row == ROW_ASR) {
            PrayerPrefs.setHanafi(this, !PrayerPrefs.hanafi(this));
        } else if (row == ROW_METHOD) {
            int n = PrayerPrefs.METHODS.length;
            PrayerPrefs.setMethodIndex(this, (PrayerPrefs.methodIndex(this) + dir + n) % n);
        } else if (row == ROW_FONT) {
            PrayerPrefs.setClockFont(this, 1 - PrayerPrefs.clockFont(this));
            applyClockFont();
        } else if (row == ROW_TEXT_SIZE) {
            PrayerPrefs.setTextSize(this, (PrayerPrefs.textSize(this) + dir + 3) % 3);
            applyTextSize();
        } else if (row == ROW_ADHAN) {
            PrayerPrefs.setAdhanMode(this, (PrayerPrefs.adhanMode(this) + dir + PrayerPrefs.ADHAN_COUNT) % PrayerPrefs.ADHAN_COUNT);
        } else if (row == ROW_FIRE) {
            PrayerPrefs.setFire(this, !PrayerPrefs.fire(this));
            applyFire();
        } else {
            int prayer = PrayerPrefs.OFFSET_PRAYERS[row - ROW_OFFSET];
            PrayerPrefs.setOffset(this, prayer, PrayerPrefs.offset(this, prayer) + dir);
        }
        refreshPrayer();
        renderPanel();
        AdhanAlarm.schedule(this);
        Log.i(TAG, "prayer settings: hanafi=" + PrayerPrefs.hanafi(this) + " method="
                + PrayerPrefs.METHODS[PrayerPrefs.methodIndex(this)] + " offsets=" + Arrays.toString(PrayerPrefs.offsets(this))
                + " clockFont=" + PrayerPrefs.CLOCK_FONTS[PrayerPrefs.clockFont(this)]
                + " textSize=" + PrayerPrefs.TEXT_SIZES[PrayerPrefs.textSize(this)]
                + " adhan=" + PrayerPrefs.adhanMode(this) + " fire=" + PrayerPrefs.fire(this) + " times=" + timesLog());
    }

    // ---------------------------------------------------------------- clock, date, prayer, weather

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    /**
     * "Now" for everything on screen. Debug builds can fake it (adb fake_time / fake_date) for a day
     * simulation; the TV's real clock and zone are never touched. Release builds always use the real clock.
     */
    private long nowMillis() {
        if (fakeMinutes < 0 && fakeDate == null) return System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        java.time.LocalDate d = fakeDate != null ? fakeDate : java.time.LocalDate.now(zone);
        java.time.LocalTime t = fakeMinutes >= 0 ? java.time.LocalTime.of(fakeMinutes / 60, fakeMinutes % 60)
                : java.time.LocalTime.now(zone);
        return ZonedDateTime.of(d, t, zone).toInstant().toEpochMilli();
    }

    private ZonedDateTime nowZoned() {
        return java.time.Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault());
    }

    /** Clocks + prayer day + date line + thought, without the weather stale check. */
    private void renderClocks() {
        clock.setText(hm(nowZoned(), DateFormat.is24HourFormat(this)));
        refreshPrayer();
    }

    private void updateClock() {
        boolean h24 = DateFormat.is24HourFormat(this);
        ZonedDateTime local = nowZoned();
        clock.setText(hm(local, h24));
        refreshPrayer(); // also redraws the date line and the second-clock line
        weather.maybeFetch(false, this::renderDateAndWeather);
    }

    /** "12:30" (or "00:30" in 24-hour mode), no AM/PM. */
    private static String hm(ZonedDateTime z, boolean h24) {
        int h = z.getHour();
        return h24 ? String.format(Locale.US, "%02d:%02d", h, z.getMinute())
                : String.format(Locale.US, "%d:%02d", h % 12 == 0 ? 12 : h % 12, z.getMinute());
    }

    /** "3:00 am" style, for the second-clock line, the panel and the logs. */
    private static String hmAmPm(ZonedDateTime z, boolean h24) {
        return h24 ? hm(z, true) : hm(z, false) + (z.getHour() < 12 ? " am" : " pm");
    }

    private String clockText(long millis) {
        return hmAmPm(java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()), DateFormat.is24HourFormat(this));
    }

    /**
     * Header clock font from Prayer settings: Cormorant Garamond Light (wght 300) or Outfit Thin (wght 200).
     * The Kitchener line uses the same family a little heavier (500 / 300) so it stays readable at ~32px.
     */
    /**
     * "Text size" (Normal / Large / Larger) for the secondary text, in sp (= px at 1080p / 2). The main clock and
     * the dock never change. Spans that embed icons are rebuilt afterwards because their size follows the text.
     */
    private void applyTextSize() {
        int l = PrayerPrefs.textSize(this);
        float[] hereT = {24, 28, 32}, dateT = {14, 16, 18}, awayT = {22, 26, 30}, awayR = {10, 13, 15},
                times = {15, 17, 19}, names = {11, 13, 15}, tag = {10, 11, 12}, text = {19, 21, 23}, src = {10, 12, 14};
        hereTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, hereT[l]);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, dateT[l]);
        awayClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, awayT[l]);
        awayTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, awayT[l]);
        awayRange.setTextSize(TypedValue.COMPLEX_UNIT_SP, awayR[l]);
        float flag = awayT[l] / 22f; // the 22x15px flag grows with the second-clock time
        ViewGroup.LayoutParams fl = awayFlag.getLayoutParams();
        fl.width = dp(11 * flag);
        fl.height = dp(7.5f * flag);
        awayFlag.setLayoutParams(fl);
        int timelineDp = Math.max(76, timeline.setTextScale(times[l], names[l]));
        timeline.getLayoutParams().height = dp(timelineDp);
        timeline.requestLayout();
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) thought.getLayoutParams();
        // Below the timeline; without prayer times the thought takes the timeline's place.
        tp.topMargin = cfg.prayerEnabled ? dp(178 + timelineDp + 12) : dp(214);
        thought.setLayoutParams(tp);
        thoughtTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, tag[l]);
        thoughtText.setTextSize(TypedValue.COMPLEX_UNIT_SP, text[l]);
        thoughtSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, src[l]);
        if (day != null) renderDateAndWeather();
    }

    private void applyClockFont() {
        boolean outfit = PrayerPrefs.clockFont(this) == 1;
        Typeface tf = Fonts.get(this, outfit ? R.font.outfit : R.font.cormorant_garamond);
        clock.setTypeface(tf);
        Fonts.weight(clock, outfit ? "'wght' 200" : "'wght' 300");
        awayClock.setTypeface(tf);
        Fonts.weight(awayClock, outfit ? "'wght' 300" : "'wght' 500");
        // Temperatures in the clock family: light beside the main clock, as heavy as the second-clock time beside it.
        hereTemp.setTypeface(tf);
        Fonts.weight(hereTemp, outfit ? "'wght' 200" : "'wght' 300");
        awayTemp.setTypeface(tf);
        Fonts.weight(awayTemp, outfit ? "'wght' 300" : "'wght' 500");
        // Cormorant's default figures are old-style (x-height); clocks want lining figures.
        for (TextView t : new TextView[]{clock, awayClock, hereTemp, awayTemp}) t.setFontFeatureSettings("lnum");
    }

    /**
     * Recompute today's sun/prayer times (settings or minute changed) and push them to the timeline and the
     * sky. With prayer_enabled=false the same astronomy only drives the sky (dawn, sunrise, noon, afternoon,
     * sunset, night) and the timeline stays hidden.
     */
    private void refreshPrayer() {
        long now = nowMillis();
        day = cfg.prayerEnabled ? PrayerPrefs.day(this, now) : PrayerPrefs.skyDay(this, now);
        if (cfg.prayerEnabled) timeline.setDay(day);
        sky.update(day);
        renderDateAndWeather();
        renderThought();
        if (BuildConfig.DEBUG && cfg.prayerEnabled) timeline.post(this::logExpectedDot);
    }

    private void renderDateAndWeather() {
        if (day == null) return;
        Weather.City[] w = weather.current();
        boolean h24 = DateFormat.is24HourFormat(this);
        ZonedDateTime local = nowZoned();
        ZonedDateTime away = local.withZoneSameInstant(cfg.secondZone);
        String weekday = away.toLocalDate().equals(local.toLocalDate()) ? ""
                : away.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " ";
        awayClock.setText(weekday + hmAmPm(away, h24));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, DateFormat.format("EEEE d MMMM", nowMillis()), Lapis.PLASTER_100);
        if (cfg.prayerEnabled) {
            append(sb, "   ·   ", Lapis.MIST_400);
            append(sb, day.hijri(this), Lapis.GOLD_400);
        }
        if (w != null && w[0].hasRange) {
            append(sb, "   ·   ", Lapis.MIST_400);
            sb.append(rangeText(w[0], Lapis.MIST_400));
        }
        date.setText(sb);

        // Weather sits beside each clock; hidden entirely (no gap) when never fetched or older than 3 h.
        boolean awayW = w != null && w.length > 1;
        hereWeather.setVisibility(w == null ? View.GONE : View.VISIBLE);
        awayTemp.setVisibility(awayW ? View.VISIBLE : View.GONE);
        awayRange.setVisibility(awayW && w[1].hasRange ? View.VISIBLE : View.GONE);
        if (w == null) return;
        hereTemp.setText(tempText(w[0], Lapis.PLASTER_100, hereTemp.getTextSize()));
        if (!awayW) return;
        awayTemp.setText(tempText(w[1], Lapis.MIST_400, awayTemp.getTextSize()));
        awayRange.setText(rangeText(w[1], Lapis.alpha(Lapis.MIST_400, 0.7f)));
    }

    /** "[icon] 32°" */
    private CharSequence tempText(Weather.City c, int color, float textPx) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendIcon(sb, Weather.icon(c), color, textPx * 0.8f);
        sb.append(" ").append(String.valueOf(c.temp)).append("°");
        return sb;
    }

    /** "↑34° ↓22°" in mist at ~70%. */
    private static CharSequence rangeText(Weather.City c, int color) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "↑" + c.max + "°  ↓" + c.min + "°", color);
        // Alexandria's arrows are tiny; draw them with the system sans so they read at 3 m.
        for (int i = 0; i < sb.length(); i++) {
            char ch = sb.charAt(i);
            if (ch == '↑' || ch == '↓') {
                sb.setSpan(new SystemSans(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /** TypefaceSpan(Typeface) is Android 9. This draws the arrows in the system sans on Android 7. */
    private static final class SystemSans extends MetricAffectingSpan {
        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setTypeface(Typeface.DEFAULT);
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            tp.setTypeface(Typeface.DEFAULT);
        }
    }

    /** Thought of the day. A new date starts again at the stable pick; the timer then walks the list. */
    private void renderThought() {
        if (!cfg.thoughtsEnabled) {
            thought.setVisibility(View.GONE);
            return;
        }
        LocalDate today = thoughtToday();
        if (today.equals(shownThoughtDate)) return;
        thoughtSlot = 0;
        bindThought(today, false);
    }

    private final Runnable rotateThought = () -> {
        if (!resumed || !cfg.thoughtsEnabled) return;
        LocalDate today = thoughtToday();
        if (!today.equals(shownThoughtDate)) thoughtSlot = 0;
        else thoughtSlot++;
        bindThought(today, true);
        scheduleThoughtRotation();
    };

    private void scheduleThoughtRotation() {
        main.removeCallbacks(rotateThought);
        if (resumed && cfg.thoughtsEnabled && thoughts.count(thoughtToday()) > 1) {
            main.postDelayed(rotateThought, THOUGHT_EVERY_MS);
        }
    }

    private LocalDate thoughtToday() {
        return fakeDate != null ? fakeDate
                : java.time.Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void bindThought(LocalDate today, boolean fade) {
        Thoughts.Thought t = thoughts.at(today, thoughtSlot);
        shownThoughtDate = today;
        if (t == null) {
            thought.setVisibility(View.GONE);
            return;
        }
        Runnable apply = () -> {
            thought.setVisibility(View.VISIBLE);
            thought.setAlpha(fade ? 0f : 1f);
            thoughtTag.setText(t.friday ? R.string.jumuah : R.string.thought_today);
            thoughtText.setText("“" + t.text + "”");
            thoughtSource.setText(t.source);
            thoughtSource.setVisibility(t.source == null ? View.GONE : View.VISIBLE);
            if (fade) thought.animate().alpha(1f).setDuration(320).start();
        };
        Log.i(TAG, "thought: date=" + today + " slot=" + thoughtSlot + " friday=" + t.friday + " source=" + t.source);
        if (!fade || thought.getVisibility() != View.VISIBLE) {
            thought.animate().cancel();
            apply.run();
            return;
        }
        thought.animate().cancel();
        thought.animate().alpha(0f).setDuration(280).withEndAction(apply).start();
    }

    private int adhanShownMode = PrayerPrefs.ADHAN_OFF;

    private final AdhanPlayer.Cue adhanCue = new AdhanPlayer.Cue() {
        @Override
        public void onAdhan(int prayer, int mode) {
            adhanShownMode = mode;
            showAdhanBlob(prayer, mode);
            fire.setAdhan(true);
        }

        @Override
        public void onAdhanEnd() {
            // Follow the clip that just played. A beep stays up briefly; the spoken call ends with the audio.
            if (adhanShownMode == PrayerPrefs.ADHAN_BEEP) {
                main.removeCallbacks(hideAdhanBlob);
                main.postDelayed(hideAdhanBlob, 6_000);
            } else {
                hideAdhanBlob.run();
            }
            fire.setAdhan(false);
        }
    };

    private void applyFire() {
        boolean on = PrayerPrefs.fire(this);
        sky.setVisibility(on ? View.GONE : View.VISIBLE);
        fire.setAdhan(AdhanPlayer.activePrayer() >= 0);
        fire.setEnabled(on);
    }

    private void showAdhanBlob(int prayer, int mode) {
        if (prayer < 0 || prayer >= getResources().getStringArray(R.array.prayer_names).length) return;
        adhanBlob.setText(getResources().getStringArray(R.array.prayer_names)[prayer]);
        adhanBlob.setAlpha(0f);
        adhanBlob.setVisibility(View.VISIBLE);
        adhanBlob.animate().alpha(1f).setDuration(180).start();
        main.removeCallbacks(hideAdhanBlob);
        if (mode == PrayerPrefs.ADHAN_BEEP) main.postDelayed(hideAdhanBlob, 6_000);
    }

    private final Runnable hideAdhanBlob = () -> {
        adhanBlob.animate().alpha(0f).setDuration(220).withEndAction(() -> adhanBlob.setVisibility(View.GONE)).start();
    };

    private static void append(SpannableStringBuilder sb, CharSequence text, int color) {
        int s = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** A weather icon at ~0.9× the text size, centred on the line. */
    private void appendIcon(SpannableStringBuilder sb, int res, int color, float textPx) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        int size = Math.round(textPx * 0.9f * 1.15f); // the 24-unit icon has ~2 units of padding per side
        d.setBounds(0, 0, size, size);
        int s = sb.length();
        sb.append("￼");
        // ALIGN_CENTER is API 29; Android 9 sits the icon on the line's bottom instead.
        int align = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? DynamicDrawableSpan.ALIGN_CENTER : DynamicDrawableSpan.ALIGN_BOTTOM;
        sb.setSpan(new ImageSpan(d, align), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String timesLog() {
        StringBuilder sb = new StringBuilder();
        for (int p : PrayerTimeline.COLUMNS) sb.append(PrayerDay.NAMES[p]).append('=').append(clockText(day.times[p])).append(' ');
        return sb.toString().trim();
    }

    /**
     * Debug receipt: the dot x the timeline should draw, computed here from the times and the view width
     * (independently of PrayerTimeline.onDraw, which logs the x it actually drew).
     */
    private void logExpectedDot() {
        int w = timeline.getWidth();
        if (w == 0 || day == null) return;
        float col = w / 5f;
        long now = day.now;
        long[] t = new long[5];
        for (int i = 0; i < 5; i++) t[i] = day.times[PrayerTimeline.COLUMNS[i]];
        String seg;
        float frac, x;
        if (now < t[0]) {
            frac = PrayerTimeline.fraction(now, day.yesterdayIsha, t[0]);
            x = col * 0.5f * frac;
            seg = "Isha(yesterday)->Fajr";
        } else if (now >= t[4]) {
            frac = PrayerTimeline.fraction(now, t[4], day.tomorrowFajr);
            x = col * 4.5f + col * 0.5f * frac;
            seg = "Isha->Fajr(tomorrow)";
        } else {
            int k = 0;
            while (k < 3 && now >= t[k + 1]) k++;
            frac = (now - t[k]) / (float) (t[k + 1] - t[k]);
            x = col * (k + 0.5f) + col * frac;
            seg = PrayerDay.NAMES[PrayerTimeline.COLUMNS[k]] + "->" + PrayerDay.NAMES[PrayerTimeline.COLUMNS[k + 1]];
        }
        Log.i(TAG, String.format(Locale.US, "timeline expected: now=%s%s segment=%s fraction=%.4f expectedX=%.1f times=%s",
                clockText(now), fakeMinutes >= 0 || fakeDate != null ? " (fake)" : "", seg, frac, x, timesLog()));
    }

    /** Debug builds only: adb preview hooks for the sky, the timeline clock and the weather. */
    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String period = intent.getStringExtra("period");
            if (period != null) {
                sky.forcedPeriod = Arrays.asList(SkyView.PERIODS).indexOf(period); // "auto" → -1
                if (sky.forcedPeriod < 0) sky.forcedPattern = -1;
            }
            if (intent.hasExtra("pattern")) sky.forcedPattern = intent.getIntExtra("pattern", -1);
            String fd = intent.getStringExtra("fake_date");
            if (fd != null) {
                try {
                    fakeDate = "auto".equals(fd) ? null : LocalDate.parse(fd);
                } catch (RuntimeException bad) {
                    fakeDate = null;
                }
                shownThoughtDate = null;
            }
            String fake = intent.getStringExtra("fake_time");
            if (fake != null) {
                fakeMinutes = -1;
                String[] hm = fake.split(":");
                if (hm.length == 2) fakeMinutes = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
            }
            Log.i(TAG, "debug: period=" + sky.forcedPeriod + " pattern=" + sky.forcedPattern + " fakeMinutes=" + fakeMinutes
                    + " fakeDate=" + fakeDate + " now=" + nowZoned());
            if (intent.getBooleanExtra("weather_refresh", false)) weather.maybeFetch(true, MainActivity.this::renderDateAndWeather);
            if (intent.getBooleanExtra("rotate", false)) rotateThought.run();
            String adhan = intent.getStringExtra("adhan");
            if (adhan != null) {
                int mode = "takbir".equals(adhan) ? PrayerPrefs.ADHAN_TAKBIR
                        : "beep".equals(adhan) ? PrayerPrefs.ADHAN_BEEP
                        : "off".equals(adhan) ? PrayerPrefs.ADHAN_OFF : PrayerPrefs.ADHAN_FULL;
                AdhanPlayer.start(MainActivity.this, PrayerDay.DHUHR, 0, true, mode);
            }
            // Everything time-based (both clocks, weekday prefix, date line, sky, timeline, thought) reads nowMillis().
            renderClocks();
        }
    };

    // ---------------------------------------------------------------- network pill

    // Android 12+ only hands the SSID to callbacks that ask for location info; older versions read it from WifiManager.
    private final ConnectivityManager.NetworkCallback networkCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? new NetworkWatcher(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
            : new NetworkWatcher();

    private final class NetworkWatcher extends ConnectivityManager.NetworkCallback {
        NetworkWatcher() {
            super();
        }

        @RequiresApi(Build.VERSION_CODES.S)
        NetworkWatcher(int flags) {
            super(flags);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            ssid.setText(networkLabel(caps));
        }

        @Override
        public void onLost(Network network) {
            ssid.setText(R.string.offline);
        }
    }

    /**
     * SSID needs ACCESS_FINE_LOCATION (granted over adb), and on Android 9-10 Location turned on too;
     * otherwise plain "Wi-Fi".
     */
    @SuppressWarnings("deprecation") // WifiManager.getConnectionInfo: the only SSID source before Android 12
    private String networkLabel(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return getString(R.string.ethernet);
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return getString(R.string.wifi);
        WifiInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info = caps.getTransportInfo() instanceof WifiInfo ? (WifiInfo) caps.getTransportInfo() : null;
        } else {
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            info = wifi == null ? null : wifi.getConnectionInfo();
        }
        String ssid = info == null ? null : info.getSSID();
        if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID)) return getString(R.string.wifi);
        ssid = ssid.replaceAll("^\"|\"$", "");
        return ssid.isEmpty() ? getString(R.string.wifi) : ssid;
    }

    // ---------------------------------------------------------------- utils

    /** RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED exist from Android 13; older versions take no flag. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // the flagless call only runs below Android 13
    private void register(BroadcastReceiver receiver, IntentFilter filter, boolean exported) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, exported ? RECEIVER_EXPORTED : RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    /** The window's size: WindowMetrics from Android 11, the display's real size before (home is full screen). */
    @SuppressWarnings("deprecation") // Display.getRealSize: the pre-Android 11 equivalent
    private Rect windowBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return getWindowManager().getCurrentWindowMetrics().getBounds();
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        return new Rect(0, 0, size.x, size.y);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int i, int n) {
        return Math.max(0, Math.min(i, n - 1));
    }
}
