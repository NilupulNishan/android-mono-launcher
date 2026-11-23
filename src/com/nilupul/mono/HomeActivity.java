package com.nilupul.mono;

import android.app.Activity;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The home screen: a status line, pinned apps as plain text, and a search drawer.
 *
 * Gestures on home: swipe up = all apps + search, swipe down = notifications,
 * swipe left / right = an app you choose. Hold an app for options. "···" = menu + settings.
 */
public class HomeActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int G_NONE = 0;
    private static final int G_UP = 1;
    private static final int G_DOWN = 2;
    private static final int G_LEFT = 3;
    private static final int G_RIGHT = 4;

    // setting values
    private static final String[] CLOCKS = {"line", "big", "off"};
    private static final String[] SIZES = {"small", "medium", "large"};
    private static final String[] ALIGNS = {"left", "center", "right"};
    private static final String[] ORDERS = {"a-z", "most used"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Apps.App> shown = new ArrayList<>();
    private List<Apps.App> all = new ArrayList<>();
    private Map<String, Integer> uses = new HashMap<>();

    private LinearLayout homeView;
    private LinearLayout bigClock;
    private LinearLayout pinsView;
    private LinearLayout drawerView;
    private TextView clock;
    private TextView bigTime;
    private TextView bigDate;
    private TextView battery;
    private EditText search;
    private ListView list;
    private ResultsAdapter results;
    private GestureDetector swipe;
    private LauncherApps launcherApps;

    private boolean resumed;
    private boolean dirty;
    private boolean autoFired;
    private int gesture = G_NONE;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        all = Apps.load(this);
        uses = Apps.uses(this);
        Apps.seedDefaults(this, all);
        setContentView(build());
        applySettings();
        launcherApps = getSystemService(LauncherApps.class);
        if (launcherApps != null) launcherApps.registerCallback(appsChanged, handler);
    }

    @Override
    protected void onDestroy() {
        if (launcherApps != null) launcherApps.unregisterCallback(appsChanged);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (dirty) reload();
        applyStatusBar();
        if (Apps.isOn(this)) Apps.applyLooks(this); // grayscale + quiet status bar, if wanted
        tick.run();
        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) showBattery(sticky);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) { }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeDrawer(); // come back to the clean home view next time
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyStatusBar(); // dialogs and other apps can bring the bar back
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer(); // Home pressed while mono is showing
    }

    @Override
    public void onBackPressed() {
        // A home screen has nothing to go back to; Back only closes the drawer.
        if (drawerOpen()) closeDrawer();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!drawerOpen() && swipe != null && swipe.onTouchEvent(e) && gesture != G_NONE) {
            int g = gesture;
            gesture = G_NONE;
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel); // so a swipe never also taps an app
            cancel.recycle();
            onGesture(g);
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    private void onGesture(int g) {
        if (g == G_UP) openDrawer();
        else if (g == G_DOWN) Apps.expandNotifications(this);
        else if (g == G_LEFT) openKey(prefs().getString("swipe_left", ""));
        else if (g == G_RIGHT) openKey(prefs().getString("swipe_right", ""));
    }

    // ---------------------------------------------------------------- views

    private View build() {
        int pad = Apps.dp(this, 24);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Apps.BLACK);

        // Home view ------------------------------------------------------
        homeView = new LinearLayout(this);
        homeView.setOrientation(LinearLayout.VERTICAL);
        homeView.setPadding(pad, Apps.dp(this, 12), pad, Apps.dp(this, 8));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        clock = Apps.text(this, "", 14, Apps.GREY);
        clock.setPadding(0, Apps.dp(this, 10), Apps.dp(this, 10), Apps.dp(this, 10));
        clock.setOnClickListener(v -> openAlarms());
        battery = Apps.text(this, "", 14, Apps.GREY);
        battery.setPadding(Apps.dp(this, 10), Apps.dp(this, 10), 0, Apps.dp(this, 10));
        battery.setOnClickListener(v -> Apps.tryStart(this,
                new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "no battery settings"));
        bar.addView(clock, new LinearLayout.LayoutParams(WRAP, WRAP));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(battery, new LinearLayout.LayoutParams(WRAP, WRAP));
        homeView.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        bigClock = new LinearLayout(this);
        bigClock.setOrientation(LinearLayout.VERTICAL);
        bigClock.setPadding(0, Apps.dp(this, 28), 0, 0);
        bigTime = Apps.text(this, "", 64, Apps.WHITE);
        bigDate = Apps.text(this, "", 16, Apps.GREY);
        bigDate.setPadding(0, Apps.dp(this, 10), 0, 0);
        bigClock.addView(bigTime);
        bigClock.addView(bigDate);
        bigClock.setOnClickListener(v -> openAlarms());
        homeView.addView(bigClock, new LinearLayout.LayoutParams(MATCH, WRAP));

        homeView.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));
        pinsView = new LinearLayout(this);
        pinsView.setOrientation(LinearLayout.VERTICAL);
        homeView.addView(pinsView, new LinearLayout.LayoutParams(MATCH, WRAP));
        homeView.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView prompt = Apps.text(this, "> search_", 16, Apps.GREY);
        prompt.setPadding(0, Apps.dp(this, 16), 0, Apps.dp(this, 16));
        Apps.pressable(prompt);
        prompt.setOnClickListener(v -> openDrawer());
        TextView more = Apps.text(this, "···", 18, Apps.GREY);
        more.setPadding(Apps.dp(this, 20), Apps.dp(this, 16), 0, Apps.dp(this, 16));
        Apps.pressable(more);
        more.setOnClickListener(v -> menu());
        bottom.addView(prompt, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bottom.addView(more, new LinearLayout.LayoutParams(WRAP, WRAP));
        homeView.addView(bottom, new LinearLayout.LayoutParams(MATCH, WRAP));

        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                int minY = Apps.dp(HomeActivity.this, 48);
                int minX = Apps.dp(HomeActivity.this, 72);
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (dy < -minY) gesture = G_UP;
                    else if (dy > minY) gesture = G_DOWN;
                } else {
                    if (dx < -minX) gesture = G_LEFT;
                    else if (dx > minX) gesture = G_RIGHT;
                }
                return gesture != G_NONE;
            }
        });
        swipe.setIsLongpressEnabled(false);

        // Drawer (all apps + search) -------------------------------------
        drawerView = new LinearLayout(this);
        drawerView.setOrientation(LinearLayout.VERTICAL);
        drawerView.setPadding(pad, Apps.dp(this, 4), pad, 0);
        drawerView.setVisibility(View.GONE);

        search = new EditText(this);
        search.setTypeface(Apps.font(this));
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        search.setTextColor(Apps.WHITE);
        search.setHintTextColor(Apps.DIM);
        search.setHint("> search apps or web");
        search.setBackground(null);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(0, Apps.dp(this, 14), 0, Apps.dp(this, 14));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                filter(s.toString());
                maybeAutoOpen();
            }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_GO || enter) {
                if (!shown.isEmpty()) launch(shown.get(0));
                else if (!results.query.isEmpty()) webSearch();
                return true;
            }
            return false;
        });
        drawerView.addView(search, new LinearLayout.LayoutParams(MATCH, WRAP));

        View rule = new View(this);
        rule.setBackgroundColor(Apps.DIM);
        drawerView.addView(rule, new LinearLayout.LayoutParams(MATCH, Math.max(1, Apps.dp(this, 1) / 2)));

        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setFastScrollEnabled(true);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setSelector(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(Apps.WHITE)));
        results = new ResultsAdapter();
        list.setAdapter(results);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < shown.size()) launch(shown.get(pos));
            else webSearch();
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < shown.size()) appMenu(shown.get(pos));
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }
            @Override public void onScroll(AbsListView v, int first, int count, int total) { }
        });
        drawerView.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        root.addView(homeView, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(drawerView, new FrameLayout.LayoutParams(MATCH, MATCH));
        return root;
    }

    private void renderPins() {
        pinsView.removeAllViews();
        Map<String, Apps.App> byKey = new HashMap<>();
        for (Apps.App a : all) byKey.put(a.key(), a);

        List<String> pins = Apps.pins(this);
        if (!all.isEmpty() && pins.removeIf(k -> !byKey.containsKey(k))) {
            Apps.savePins(this, pins); // forget apps that were uninstalled
        }
        int align = alignGravity();
        float sp = pick(prefs().getString("size", "medium"), SIZES, new float[] {22, 26, 32});
        for (String k : pins) {
            Apps.App a = byKey.get(k);
            TextView t = Apps.text(this, a.label, sp, Apps.WHITE);
            t.setPadding(0, Apps.dp(this, 11), 0, Apps.dp(this, 11));
            t.setGravity(align | Gravity.CENTER_VERTICAL);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            Apps.pressable(t);
            t.setOnClickListener(v -> Apps.open(this, a));
            t.setOnLongClickListener(v -> {
                pinMenu(a);
                return true;
            });
            pinsView.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        if (pins.isEmpty()) {
            TextView t = Apps.text(this, "no pinned apps.\nswipe up, hold an app, choose \"pin to home\".", 14, Apps.GREY);
            t.setLineSpacing(0, 1.3f);
            t.setGravity(align);
            pinsView.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    // ------------------------------------------------------------- settings

    private SharedPreferences prefs() {
        return Apps.prefs(this);
    }

    private static float pick(String value, String[] keys, float[] values) {
        int i = Arrays.asList(keys).indexOf(value);
        return values[i < 0 ? 1 % values.length : i];
    }

    private int alignGravity() {
        String a = prefs().getString("align", "left");
        if ("center".equals(a)) return Gravity.CENTER_HORIZONTAL;
        if ("right".equals(a)) return Gravity.END;
        return Gravity.START;
    }

    private void applySettings() {
        String style = prefs().getString("clock", "line");
        clock.setVisibility("line".equals(style) ? View.VISIBLE : View.GONE);
        bigClock.setVisibility("big".equals(style) ? View.VISIBLE : View.GONE);
        bigClock.setGravity(alignGravity());
        applyStatusBar();
        renderPins();
        results.notifyDataSetChanged();
        tick.run();
    }

    /**
     * Hide the system status bar on mono's own screen only (mono shows time + battery itself).
     * Swiping down from the top edge shows it for a moment. Other apps are not affected.
     */
    private void applyStatusBar() {
        boolean hide = prefs().getBoolean("hide_status_bar", true);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (hide) c.hide(WindowInsets.Type.statusBars());
            else c.show(WindowInsets.Type.statusBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(hide
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : 0);
        }
    }

    private void cycle(String key, String[] values, String def) {
        int i = Arrays.asList(values).indexOf(prefs().getString(key, def));
        prefs().edit().putString(key, values[(i + 1) % values.length]).apply();
    }

    private void settingsDialog() {
        SharedPreferences p = prefs();
        boolean barHidden = p.getBoolean("hide_status_bar", true);
        boolean auto = p.getBoolean("auto_open", true);
        boolean canTweak = Apps.canTweak(this);
        boolean gray = Apps.lookWanted(this, Apps.LOOK_GRAY);
        boolean quiet = Apps.lookWanted(this, Apps.LOOK_ICONS);
        String[] items = {
                "status bar      " + (barHidden ? "hidden" : "shown"),
                "black & white   " + (!canTweak ? "needs pc setup" : gray ? "on" : "off"),
                "system icons    " + (!canTweak ? "needs pc setup" : quiet ? "quiet" : "normal"),
                "clock           " + p.getString("clock", "line"),
                "text size       " + p.getString("size", "medium"),
                "align           " + p.getString("align", "left"),
                "auto-open match " + (auto ? "on" : "off"),
                "app list order  " + p.getString("order", "a-z"),
                "swipe left  → " + nameOf(p.getString("swipe_left", "")),
                "swipe right → " + nameOf(p.getString("swipe_right", "")),
        };
        choose("settings", items, which -> {
            if (which == 0) p.edit().putBoolean("hide_status_bar", !barHidden).apply();
            else if (which == 1 || which == 2) {
                if (!canTweak) { setupDialog(); return; }
                String look = which == 1 ? Apps.LOOK_GRAY : Apps.LOOK_ICONS;
                Apps.setLookWanted(this, look, !(which == 1 ? gray : quiet));
                Apps.applyLooks(this);
            }
            else if (which == 3) cycle("clock", CLOCKS, "line");
            else if (which == 4) cycle("size", SIZES, "medium");
            else if (which == 5) cycle("align", ALIGNS, "left");
            else if (which == 6) p.edit().putBoolean("auto_open", !auto).apply();
            else if (which == 7) cycle("order", ORDERS, "a-z");
            else if (which == 8) { pickApp("swipe_left", "swipe left opens"); return; }
            else if (which == 9) { pickApp("swipe_right", "swipe right opens"); return; }
            applySettings();
            settingsDialog(); // reopen so the new value is visible
        });
    }

    private void setupDialog() {
        Ui.confirm(this, "system look",
                "while it's on, mono can turn the whole screen grey and quieten the status bar "
                        + "icons, then put both back when you turn it off.\n\nandroid only lets a pc "
                        + "allow this. with the phone plugged in, run once:\n\nadb shell pm grant "
                        + "com.nilupul.mono android.permission.WRITE_SECURE_SETTINGS\n\nor do it by "
                        + "hand: accessibility → color correction → grayscale.",
                "ok", null,
                "accessibility", () -> Apps.tryStart(this,
                        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "can't open accessibility settings"));
    }

    private void pickApp(String prefKey, String title) {
        List<Apps.App> apps = new ArrayList<>(all);
        String[] names = new String[apps.size() + 1];
        names[0] = "(nothing)";
        for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;
        choose(title, names, which -> {
            prefs().edit().putString(prefKey, which == 0 ? "" : apps.get(which - 1).key()).apply();
            settingsDialog();
        });
    }

    // --------------------------------------------------------------- drawer

    private boolean drawerOpen() {
        return drawerView != null && drawerView.getVisibility() == View.VISIBLE;
    }

    private void openDrawer() {
        if (dirty) reload();
        uses = Apps.uses(this);
        search.setText("");
        filter("");
        homeView.setVisibility(View.GONE);
        drawerView.setVisibility(View.VISIBLE);
        list.setSelection(0);
        search.requestFocus();
        handler.postDelayed(() -> {
            InputMethodManager m = getSystemService(InputMethodManager.class);
            if (m != null && drawerOpen()) m.showSoftInput(search, 0);
        }, 60);
    }

    private void closeDrawer() {
        if (!drawerOpen()) return;
        hideKeyboard();
        search.setText("");
        drawerView.setVisibility(View.GONE);
        homeView.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        InputMethodManager m = getSystemService(InputMethodManager.class);
        if (m != null && search != null) m.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void launch(Apps.App a) {
        hideKeyboard();
        Apps.open(this, a);
    }

    private void openKey(String key) {
        Apps.App a = find(key);
        if (a != null) Apps.open(this, a);
    }

    private void openAlarms() {
        Apps.tryStart(this, new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "no clock app");
    }

    private void webSearch() {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!Apps.tryStart(this, i, null)) {
            Apps.tryStart(this, new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "no browser");
        }
    }

    /** Like other minimal launchers: when exactly one app matches, open it straight away. */
    private void maybeAutoOpen() {
        if (results.query.isEmpty()) {
            autoFired = false;
            return;
        }
        if (autoFired || !prefs().getBoolean("auto_open", true)) return;
        if (shown.size() == 1) {
            autoFired = true;
            launch(shown.get(0));
        }
    }

    private int use(Apps.App a) {
        Integer n = uses.get(a.key());
        return n == null ? 0 : n;
    }

    /**
     * Best match first: name starts with the text, then initials ("gm" = google maps),
     * then a word starts with it, then contains it. Within each group, most used first.
     */
    private void filter(String raw) {
        String q = raw.trim().toLowerCase(Locale.ROOT);
        results.query = q;
        shown.clear();
        Set<String> hidden = Apps.hidden(this);
        Comparator<Apps.App> byUse = (a, b) -> Integer.compare(use(b), use(a));
        if (q.isEmpty()) {
            for (Apps.App a : all) if (!hidden.contains(a.key())) shown.add(a);
            if ("most used".equals(prefs().getString("order", "a-z"))) shown.sort(byUse);
        } else {
            // Hidden apps leave the list but can still be found by typing their name.
            List<Apps.App> starts = new ArrayList<>();
            List<Apps.App> initials = new ArrayList<>();
            List<Apps.App> words = new ArrayList<>();
            List<Apps.App> contains = new ArrayList<>();
            for (Apps.App a : all) {
                String l = a.label.toLowerCase(Locale.ROOT);
                String o = a.original.toLowerCase(Locale.ROOT);
                if (l.startsWith(q) || o.startsWith(q)) starts.add(a);
                else if (q.length() >= 2 && a.initials.startsWith(q)) initials.add(a);
                else if (l.contains(" " + q) || o.contains(" " + q)) words.add(a);
                else if (l.contains(q) || o.contains(q)) contains.add(a);
            }
            for (List<Apps.App> group : Arrays.asList(starts, initials, words, contains)) {
                group.sort(byUse);
                shown.addAll(group);
            }
        }
        results.notifyDataSetChanged();
    }

    private final class ResultsAdapter extends BaseAdapter {
        String query = "";

        @Override public int getCount() { return shown.size() + (query.isEmpty() ? 0 : 1); }
        @Override public Object getItem(int i) { return i < shown.size() ? shown.get(i) : null; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t;
            if (convert instanceof TextView) {
                t = (TextView) convert;
            } else {
                t = Apps.text(HomeActivity.this, "", 20, Apps.WHITE);
                t.setPadding(0, Apps.dp(HomeActivity.this, 12), 0, Apps.dp(HomeActivity.this, 12));
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
            }
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    pick(prefs().getString("size", "medium"), SIZES, new float[] {18, 20, 24}));
            boolean best = i == 0 && !query.isEmpty();
            if (i >= shown.size()) {
                String q = search.getText().toString().trim();
                t.setText((best ? "> " : "") + "search web: " + q);
                t.setTextColor(best ? Apps.WHITE : Apps.DIM);
            } else {
                Apps.App a = shown.get(i);
                t.setText(best ? "> " + a.label : a.label);
                t.setTextColor(best || query.isEmpty() ? Apps.WHITE : Apps.GREY);
            }
            return t;
        }
    }

    // ---------------------------------------------------------------- menus

    private void choose(String title, String[] items, Ui.Pick pick) {
        Ui.list(this, title, items, pick);
    }

    private void pinMenu(Apps.App a) {
        choose(a.label, new String[] {"move up", "move down", "rename", "unpin", "app info"}, which -> {
            List<String> pins = Apps.pins(this);
            int i = pins.indexOf(a.key());
            if (which == 0 && i > 0) Collections.swap(pins, i, i - 1);
            else if (which == 1 && i >= 0 && i < pins.size() - 1) Collections.swap(pins, i, i + 1);
            else if (which == 2) { renameDialog(a); return; }
            else if (which == 3) pins.remove(a.key());
            else if (which == 4) { Apps.openInfo(this, a.pkg); return; }
            Apps.savePins(this, pins);
            renderPins();
        });
    }

    private void appMenu(Apps.App a) {
        boolean pinned = Apps.pins(this).contains(a.key());
        boolean hiddenNow = Apps.hidden(this).contains(a.key());
        String[] items = {pinned ? "unpin" : "pin to home", "rename", hiddenNow ? "unhide" : "hide", "app info"};
        choose(a.label, items, which -> {
            if (which == 0) {
                List<String> pins = Apps.pins(this);
                if (pinned) pins.remove(a.key());
                else pins.add(a.key());
                Apps.savePins(this, pins);
                renderPins();
                Toast.makeText(this, pinned ? "unpinned" : "pinned to home", Toast.LENGTH_SHORT).show();
            } else if (which == 1) {
                renameDialog(a);
            } else if (which == 2) {
                Set<String> hidden = Apps.hidden(this);
                if (hiddenNow) hidden.remove(a.key());
                else hidden.add(a.key());
                Apps.saveHidden(this, hidden);
                filter(search.getText().toString());
            } else if (which == 3) {
                Apps.openInfo(this, a.pkg);
            }
        });
    }

    private void renameDialog(Apps.App a) {
        Ui.input(this, "rename " + a.original.toLowerCase(Locale.ROOT), a.label,
                name -> { Apps.rename(this, a, name); reload(); },
                () -> { Apps.rename(this, a, null); reload(); });
    }

    private void menu() {
        Set<String> hidden = Apps.hidden(this);
        String[] items = {"all apps", "settings", "hidden apps (" + hidden.size() + ")",
                "home app settings", "turn off mono"};
        choose("mono", items, which -> {
            if (which == 0) openDrawer();
            else if (which == 1) settingsDialog();
            else if (which == 2) hiddenDialog();
            else if (which == 3) Apps.tryStart(this,
                    new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "can't open home settings");
            else if (which == 4) confirmTurnOff();
        });
    }

    private void hiddenDialog() {
        Set<String> hidden = Apps.hidden(this);
        List<Apps.App> apps = new ArrayList<>();
        for (Apps.App a : all) if (hidden.contains(a.key())) apps.add(a);
        if (apps.isEmpty()) {
            Toast.makeText(this, "no hidden apps", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i).label;
        choose("tap to unhide", names, which -> {
            Set<String> h = Apps.hidden(this);
            h.remove(apps.get(which).key());
            Apps.saveHidden(this, h);
        });
    }

    private void confirmTurnOff() {
        Ui.confirm(this, "turn off mono?",
                "your normal home screen, colours and status bar icons come back. "
                        + "open \"mono\" from your apps to turn it on again.",
                "turn off", () -> {
                    Apps.turnOff(this);
                    finish();
                },
                null, null);
    }

    // ---------------------------------------------------- status line + data

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (clock == null) return;
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(HomeActivity.this);
            String time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(now);
            String day = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)
                    .toLowerCase(Locale.getDefault());
            clock.setText((time + " · " + day).toLowerCase(Locale.getDefault()));
            bigTime.setText(time.toLowerCase(Locale.getDefault()));
            bigDate.setText(day);
            handler.removeCallbacks(this);
            handler.postDelayed(this, 60_000 - (System.currentTimeMillis() % 60_000) + 100);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            showBattery(i);
        }
    };

    private void showBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0) {
            battery.setText("");
            return;
        }
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        battery.setText((charging ? "chg " : "bat ") + Math.round(level * 100f / scale) + "%");
    }

    private Apps.App find(String key) {
        if (key == null || key.isEmpty()) return null;
        for (Apps.App a : all) if (a.key().equals(key)) return a;
        return null;
    }

    private String nameOf(String key) {
        Apps.App a = find(key);
        return a == null ? "nothing" : a.label;
    }

    private void reload() {
        dirty = false;
        all = Apps.load(this);
        uses = Apps.uses(this);
        renderPins();
        if (drawerOpen()) filter(search.getText().toString());
    }

    private void appsChangedNow() {
        dirty = true;
        if (resumed) reload();
    }

    private final LauncherApps.Callback appsChanged = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { appsChangedNow(); }
        @Override public void onPackageAdded(String p, UserHandle u) { appsChangedNow(); }
        @Override public void onPackageChanged(String p, UserHandle u) { appsChangedNow(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { appsChangedNow(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { appsChangedNow(); }
    };
}
