package com.nilupul.mono;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.BatteryManager;
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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The home screen: a status line, pinned apps as plain text, and a search drawer.
 * Swipe up (or tap "> search") for all apps. Hold an app for options. Tap "···" for the menu.
 */
public class HomeActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Apps.App> shown = new ArrayList<>();
    private List<Apps.App> all = new ArrayList<>();

    private LinearLayout homeView;
    private LinearLayout pinsView;
    private LinearLayout drawerView;
    private TextView clock;
    private TextView battery;
    private EditText search;
    private ListView list;
    private ResultsAdapter results;
    private GestureDetector swipe;
    private LauncherApps launcherApps;

    private boolean resumed;
    private boolean dirty;
    private boolean flung;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        all = Apps.load(this);
        Apps.seedPins(this, all);
        setContentView(build());
        renderPins();
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
        if (!drawerOpen() && swipe != null && swipe.onTouchEvent(e) && flung) {
            flung = false;
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel); // so the swipe never also taps an app
            cancel.recycle();
            openDrawer();
            return true;
        }
        return super.dispatchTouchEvent(e);
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
        clock.setOnClickListener(v -> Apps.tryStart(this,
                new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "no clock app"));
        battery = Apps.text(this, "", 14, Apps.GREY);
        battery.setPadding(Apps.dp(this, 10), Apps.dp(this, 10), 0, Apps.dp(this, 10));
        battery.setOnClickListener(v -> Apps.tryStart(this,
                new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "no battery settings"));
        bar.addView(clock, new LinearLayout.LayoutParams(WRAP, WRAP));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(battery, new LinearLayout.LayoutParams(WRAP, WRAP));
        homeView.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

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
                if (dy < -Apps.dp(HomeActivity.this, 48) && Math.abs(dy) > Math.abs(dx)) {
                    flung = true;
                    return true;
                }
                return false;
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
        search.setHint("> search");
        search.setBackground(null);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(0, Apps.dp(this, 14), 0, Apps.dp(this, 14));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_GO || enter) {
                if (!shown.isEmpty()) launch(shown.get(0));
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
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setSelector(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(Apps.WHITE)));
        results = new ResultsAdapter();
        list.setAdapter(results);
        list.setOnItemClickListener((p, v, pos, id) -> launch(shown.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            appMenu(shown.get(pos));
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
        for (String k : pins) {
            Apps.App a = byKey.get(k);
            TextView t = Apps.text(this, a.label, 26, Apps.WHITE);
            t.setPadding(0, Apps.dp(this, 11), 0, Apps.dp(this, 11));
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
            pinsView.addView(t);
        }
    }

    // --------------------------------------------------------------- drawer

    private boolean drawerOpen() {
        return drawerView != null && drawerView.getVisibility() == View.VISIBLE;
    }

    private void openDrawer() {
        if (dirty) reload();
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

    /** Best match first: name starts with the text, then a word starts with it, then contains it. */
    private void filter(String raw) {
        String q = raw.trim().toLowerCase(Locale.ROOT);
        results.query = q;
        shown.clear();
        Set<String> hidden = Apps.hidden(this);
        if (q.isEmpty()) {
            for (Apps.App a : all) if (!hidden.contains(a.key())) shown.add(a);
        } else {
            // Hidden apps leave the list but can still be found by typing their name.
            List<Apps.App> starts = new ArrayList<>();
            List<Apps.App> words = new ArrayList<>();
            List<Apps.App> contains = new ArrayList<>();
            for (Apps.App a : all) {
                String l = a.label.toLowerCase(Locale.ROOT);
                String o = a.original.toLowerCase(Locale.ROOT);
                if (l.startsWith(q) || o.startsWith(q)) starts.add(a);
                else if (l.contains(" " + q) || o.contains(" " + q)) words.add(a);
                else if (l.contains(q) || o.contains(q)) contains.add(a);
            }
            shown.addAll(starts);
            shown.addAll(words);
            shown.addAll(contains);
        }
        results.notifyDataSetChanged();
    }

    private final class ResultsAdapter extends BaseAdapter {
        String query = "";

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
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
            Apps.App a = shown.get(i);
            boolean best = i == 0 && !query.isEmpty();
            t.setText(best ? "> " + a.label : a.label);
            t.setTextColor(best || query.isEmpty() ? Apps.WHITE : Apps.GREY);
            return t;
        }
    }

    // ---------------------------------------------------------------- menus

    private interface Pick {
        void on(int which);
    }

    private void choose(String title, String[] items, Pick pick) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(items, (d, which) -> pick.on(which))
                .show();
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
        EditText e = new EditText(this);
        e.setTypeface(Apps.font(this));
        e.setSingleLine(true);
        e.setText(a.label);
        e.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        int p = Apps.dp(this, 20);
        wrap.setPadding(p, Apps.dp(this, 8), p, 0);
        wrap.addView(e);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("rename " + a.original)
                .setView(wrap)
                .setPositiveButton("save", (d, w) -> { Apps.rename(this, a, e.getText().toString()); reload(); })
                .setNeutralButton("reset", (d, w) -> { Apps.rename(this, a, null); reload(); })
                .setNegativeButton("cancel", null)
                .show();
    }

    private void menu() {
        Set<String> hidden = Apps.hidden(this);
        String[] items = {"all apps", "hidden apps (" + hidden.size() + ")", "home app settings", "turn off mono"};
        choose("mono", items, which -> {
            if (which == 0) openDrawer();
            else if (which == 1) hiddenDialog();
            else if (which == 2) Apps.tryStart(this,
                    new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "can't open home settings");
            else if (which == 3) confirmTurnOff();
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
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("turn off mono?")
                .setMessage("your normal OPPO home screen comes back. open \"mono\" from your apps to turn it on again.")
                .setPositiveButton("turn off", (d, w) -> {
                    Apps.turnOff(this);
                    finish();
                })
                .setNegativeButton("cancel", null)
                .show();
    }

    // ---------------------------------------------------- status line + data

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(HomeActivity.this);
            String time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(now);
            String day = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now);
            clock.setText((time + " · " + day).toLowerCase(Locale.getDefault()));
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

    private void reload() {
        dirty = false;
        all = Apps.load(this);
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
