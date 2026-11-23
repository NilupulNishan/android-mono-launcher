package com.nilupul.mono;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.service.notification.NotificationListenerService;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** App list, saved choices (pins, hidden, renames, settings), styling and the on/off switch. */
final class Apps {

    static final int WHITE = 0xFFFFFFFF;
    static final int GREY = 0xFF8A8A8A;
    static final int DIM = 0xFF4A4A4A;
    static final int BLACK = 0xFF000000;

    private Apps() {}

    // ---------------------------------------------------------------- apps

    static final class App {
        final String pkg;
        final String cls;
        final String original;
        final String label;
        final String initials;

        App(String pkg, String cls, String original, String label) {
            this.pkg = pkg;
            this.cls = cls;
            this.original = original;
            this.label = label;
            StringBuilder b = new StringBuilder();
            for (String w : label.split("[\\s._-]+")) if (!w.isEmpty()) b.append(w.charAt(0));
            this.initials = b.toString().toLowerCase(Locale.ROOT);
        }

        String key() {
            return pkg + "/" + cls;
        }
    }

    /** Every app that has an icon in the normal app list, sorted by name. */
    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        SharedPreferences p = prefs(c);
        List<App> out = new ArrayList<>(found.size());
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            String cls = r.activityInfo.name;
            String original = String.valueOf(r.loadLabel(pm)).trim();
            String renamed = p.getString("name:" + pkg + "/" + cls, null);
            String label = renamed != null ? renamed : original.toLowerCase(Locale.ROOT);
            out.add(new App(pkg, cls, original, label));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    static void open(Context c, App a) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(a.pkg, a.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (tryStart(c, i, "can't open " + a.label)) countUse(c, a);
    }

    static void openInfo(Context c, String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tryStart(c, i, "can't open app info");
    }

    static boolean tryStart(Context c, Intent i, String error) {
        try {
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            if (error != null) Toast.makeText(c, error, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /**
     * Pull down the notification shade (needs only EXPAND_STATUS_BAR, a normal
     * auto-granted permission that can do nothing else). Same call other launchers use.
     */
    static void expandNotifications(Context c) {
        try {
            Object sb = c.getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception e) {
            Toast.makeText(c, "can't open notifications here; swipe from the top edge", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------ system look while mono is on
    //
    // "Looks" are Android system settings mono switches on together with itself and
    // puts back exactly as they were when mono is turned off. Each previous value is
    // saved before the first change. Needs WRITE_SECURE_SETTINGS (one-time PC grant).

    static final String LOOK_GRAY = "gray";
    static final String LOOK_ICONS = "icons";

    private static final String GRAY_ON = "accessibility_display_daltonizer_enabled";
    private static final String GRAY_MODE = "accessibility_display_daltonizer";
    private static final String ICONS = "icon_blacklist";
    private static final String NULL = "\u0000null";

    /** Accessibility -> Color correction -> Grayscale, for the whole screen. */
    private static final String[][] GRAY = {{GRAY_MODE, "0"}, {GRAY_ON, "1"}};

    /** Status bar keeps time, signal and battery; these system icons are hidden. */
    private static final String[][] QUIET_ICONS = {{ICONS,
            "volume,zen,mute,bluetooth,hotspot,headset,alarm_clock,location,nfc,rotate,vpn,"
            + "data_saver,speakerphone,cast"}};

    /** True once the one-time PC grant has been done (adb shell pm grant ... WRITE_SECURE_SETTINGS). */
    static boolean canTweak(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean lookWanted(Context c, String look) {
        return prefs(c).getBoolean(LOOK_GRAY.equals(look) ? "bw_screen" : "quiet_icons", true);
    }

    static void setLookWanted(Context c, String look, boolean on) {
        prefs(c).edit().putBoolean(LOOK_GRAY.equals(look) ? "bw_screen" : "quiet_icons", on).apply();
    }

    /** mono is in use: apply each look the user wants, undo the ones they don't. */
    static void applyLooks(Context c) {
        setLook(c, LOOK_GRAY, lookWanted(c, LOOK_GRAY));
        setLook(c, LOOK_ICONS, lookWanted(c, LOOK_ICONS));
        setQuiet(c, prefs(c).getBoolean(N_QUIET, false));
        syncNotifService(c);
    }

    /** mono is off: put every system setting back the way it was, stop the notification service. */
    static void restoreLooks(Context c) {
        setLook(c, LOOK_GRAY, false);
        setLook(c, LOOK_ICONS, false);
        setQuiet(c, false);
        syncNotifService(c);
    }

    private static void setLook(Context c, String look, boolean on) {
        if (!canTweak(c)) return;
        SharedPreferences p = prefs(c);
        migrateOldGray(p);
        String[][] values = LOOK_GRAY.equals(look) ? GRAY : QUIET_ICONS;
        String flag = "applied:" + look;
        ContentResolver r = c.getContentResolver();
        try {
            if (on) {
                if (!p.getBoolean(flag, false)) {
                    SharedPreferences.Editor e = p.edit();
                    for (String[] kv : values) {
                        String prev = Settings.Secure.getString(r, kv[0]);
                        e.putString("prev:" + kv[0], prev == null ? NULL : prev);
                    }
                    e.putBoolean(flag, true).commit(); // saved before anything changes
                }
                for (String[] kv : values) {
                    if (!kv[1].equals(Settings.Secure.getString(r, kv[0]))) Settings.Secure.putString(r, kv[0], kv[1]);
                }
            } else if (p.getBoolean(flag, false)) {
                for (String[] kv : values) {
                    String prev = p.getString("prev:" + kv[0], NULL);
                    Settings.Secure.putString(r, kv[0], NULL.equals(prev) ? null : prev);
                }
                p.edit().putBoolean(flag, false).commit();
            }
        } catch (RuntimeException ignored) {
            // permission revoked or setting blocked by the system: leave it as it is
        }
    }

    /** v1.2 saved grayscale state under other keys; carry it over so it still restores correctly. */
    private static void migrateOldGray(SharedPreferences p) {
        if (!p.contains("gray_applied")) return;
        SharedPreferences.Editor e = p.edit();
        if (p.getBoolean("gray_applied", false)) {
            e.putString("prev:" + GRAY_ON, String.valueOf(p.getInt("gray_prev_on", 0)));
            int mode = p.getInt("gray_prev_mode", -1);
            e.putString("prev:" + GRAY_MODE, mode < 0 ? NULL : String.valueOf(mode));
            e.putBoolean("applied:" + LOOK_GRAY, true);
        }
        e.remove("gray_applied").remove("gray_prev_on").remove("gray_prev_mode").commit();
    }

    // ------------------------------------------------------ notifications
    //
    // All four features are off by default and only act while mono is on.

    static final String N_MARKERS = "n_markers";   // "·3" after app names
    static final String N_LIST = "n_list";         // notification list on home
    static final String N_FOCUS = "n_focus";       // remove notifications from chosen apps
    static final String N_QUIET = "n_quiet";       // Do Not Disturb (priority only)
    private static final String N_FOCUS_APPS = "n_focus_apps";

    static ComponentName notifyService(Context c) {
        return new ComponentName(c, NotifyService.class);
    }

    /** Markers, list and focus need the listener; quiet does not. */
    static boolean listenerWanted(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean(N_MARKERS, false) || p.getBoolean(N_LIST, false) || p.getBoolean(N_FOCUS, false);
    }

    static boolean notifAccess(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(notifyService(c));
    }

    static boolean dndAccess(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    /** True when the listener should be connected: mono is on and a feature needs it. */
    static boolean listenerShouldRun(Context c) {
        return listenerWanted(c) && isOn(c);
    }

    /**
     * Connect the listener only while mono is on and a feature needs it.
     *
     * Pausing uses Android's requestUnbind()/requestRebind() pair. Disabling the component
     * is only done while access is not granted: once Android has dropped an approved
     * listener because its component was disabled, re-enabling it does not reconnect it.
     */
    static void syncNotifService(Context c) {
        boolean want = listenerShouldRun(c);
        boolean access = notifAccess(c);
        PackageManager pm = c.getPackageManager();
        ComponentName cn = notifyService(c);
        boolean enabled = pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        if (want && !enabled) {
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } else if (!want && !access && enabled) {
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        if (want) {
            if (access && !Notifs.isConnected()) {
                try {
                    NotificationListenerService.requestRebind(cn); // resumes after requestUnbind()
                } catch (RuntimeException ignored) { }
            }
        } else {
            NotifyService live = NotifyService.live;
            if (live != null) {
                try {
                    live.requestUnbind(); // pause: Android disconnects it and stops calling it
                } catch (RuntimeException ignored) { }
            }
            Notifs.connected(false);
            Notifs.clearHidden(null);
        }
    }

    static Set<String> focusApps(Context c) {
        return new HashSet<>(prefs(c).getStringSet(N_FOCUS_APPS, Collections.emptySet()));
    }

    static void saveFocusApps(Context c, Set<String> pkgs) {
        prefs(c).edit().putStringSet(N_FOCUS_APPS, new HashSet<>(pkgs)).apply();
    }

    static void openNotifAccess(Context c) {
        Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, notifyService(c).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 30 && tryStart(c, detail, null)) return;
        tryStart(c, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "can't open notification access");
    }

    static void openDndAccess(Context c) {
        tryStart(c, new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "can't open do not disturb access");
    }

    /**
     * Quiet: Do Not Disturb in "priority only" mode (your own DND exceptions still ring).
     * Switched on once when wanted, so a change you make yourself afterwards is respected;
     * on turn-off the previous mode comes back, unless you changed it in between.
     */
    static void setQuiet(Context c, boolean on) {
        if (!dndAccess(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        SharedPreferences p = prefs(c);
        boolean applied = p.getBoolean("applied:quiet", false);
        try {
            if (on && !applied) {
                p.edit().putInt("prev:dnd", nm.getCurrentInterruptionFilter())
                        .putBoolean("applied:quiet", true).commit();
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            } else if (!on && applied) {
                if (nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    int prev = p.getInt("prev:dnd", NotificationManager.INTERRUPTION_FILTER_ALL);
                    if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                        prev = NotificationManager.INTERRUPTION_FILTER_ALL;
                    }
                    nm.setInterruptionFilter(prev);
                }
                p.edit().putBoolean("applied:quiet", false).commit();
            }
        } catch (RuntimeException ignored) {
            // access revoked meanwhile: leave Do Not Disturb as it is
        }
    }

    // ------------------------------------------------------- usage counts

    /** Launch counts, kept only on the phone, for "most used" order and search ranking. */
    private static SharedPreferences usage(Context c) {
        return c.getSharedPreferences("mono_usage", Context.MODE_PRIVATE);
    }

    private static void countUse(Context c, App a) {
        SharedPreferences u = usage(c);
        u.edit().putInt(a.key(), u.getInt(a.key(), 0) + 1).apply();
    }

    static Map<String, Integer> uses(Context c) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, ?> e : usage(c).getAll().entrySet()) {
            if (e.getValue() instanceof Integer) out.put(e.getKey(), (Integer) e.getValue());
        }
        return out;
    }

    // --------------------------------------------------------- saved state

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mono", Context.MODE_PRIVATE);
    }

    static List<String> pins(Context c) {
        String raw = prefs(c).getString("pins", "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    static void savePins(Context c, List<String> pins) {
        prefs(c).edit().putString("pins", String.join("\n", pins)).apply();
    }

    static Set<String> hidden(Context c) {
        return new HashSet<>(prefs(c).getStringSet("hidden", Collections.emptySet()));
    }

    static void saveHidden(Context c, Set<String> hidden) {
        prefs(c).edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
    }

    static void rename(Context c, App a, String name) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (name == null || name.trim().isEmpty()) e.remove("name:" + a.key());
        else e.putString("name:" + a.key(), name.trim());
        e.apply();
    }

    /**
     * First run: pin phone, messages, WhatsApp, Chrome, camera, gallery and settings if present,
     * and set swipe left = camera, swipe right = phone.
     */
    static void seedDefaults(Context c, List<App> all) {
        SharedPreferences p = prefs(c);
        boolean needPins = !p.getBoolean("seeded", false);
        boolean needLeft = !p.contains("swipe_left");
        boolean needRight = !p.contains("swipe_right");
        if (!needPins && !needLeft && !needRight) return;

        String dialer = null;
        String sms = null;
        String camera = null;
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            if (tm != null) dialer = tm.getDefaultDialerPackage();
        } catch (RuntimeException ignored) { }
        try {
            sms = Telephony.Sms.getDefaultSmsPackage(c);
        } catch (RuntimeException ignored) { }
        ResolveInfo cam = c.getPackageManager().resolveActivity(
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), PackageManager.MATCH_DEFAULT_ONLY);
        if (cam != null && cam.activityInfo != null) camera = cam.activityInfo.packageName;

        Map<String, App> firstByPkg = new LinkedHashMap<>();
        for (App a : all) if (!firstByPkg.containsKey(a.pkg)) firstByPkg.put(a.pkg, a);

        SharedPreferences.Editor e = p.edit();
        if (needPins) {
            String[] wanted = {dialer, sms, "com.whatsapp.w4b", "com.whatsapp", "com.android.chrome",
                    camera, "com.coloros.gallery3d", "com.google.android.apps.photos", "com.android.settings"};
            List<String> pins = new ArrayList<>();
            for (String pkg : wanted) {
                App a = pkg == null ? null : firstByPkg.get(pkg);
                if (a != null && !pins.contains(a.key())) pins.add(a.key());
            }
            e.putString("pins", String.join("\n", pins)).putBoolean("seeded", true);
        }
        if (needLeft) {
            App a = camera == null ? null : firstByPkg.get(camera);
            e.putString("swipe_left", a == null ? "" : a.key());
        }
        if (needRight) {
            App a = dialer == null ? null : firstByPkg.get(dialer);
            e.putString("swipe_right", a == null ? "" : a.key());
        }
        e.apply();
    }

    // ------------------------------------------------------------- styling

    private static Typeface font;

    static Typeface font(Context c) {
        if (font == null) {
            try {
                font = Typeface.createFromAsset(c.getAssets(), "JetBrainsMono-Regular.ttf");
            } catch (RuntimeException e) {
                font = Typeface.MONOSPACE;
            }
        }
        return font;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTypeface(font(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Subtle grey ripple so taps give feedback without colour. */
    static void pressable(TextView t) {
        t.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(WHITE)));
        t.setClickable(true);
        t.setFocusable(true);
    }

    // ------------------------------------------------------- on/off switch

    static ComponentName home(Context c) {
        return new ComponentName(c, HomeActivity.class);
    }

    static boolean homeEnabled(Context c) {
        // Manifest default is disabled, so only an explicit "enabled" counts.
        return c.getPackageManager().getComponentEnabledSetting(home(c))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static void setHomeEnabled(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(home(c),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                   : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    static boolean roleHeld(Context c) {
        RoleManager rm = c.getSystemService(RoleManager.class);
        return rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME);
    }

    /** mono is "on" only when its home screen exists and Android uses it as the home app. */
    static boolean isOn(Context c) {
        return homeEnabled(c) && roleHeld(c);
    }

    /** Turn mono off: remove its home screen, so Android goes back to the phone's own launcher. */
    static void turnOff(Activity a) {
        setHomeEnabled(a, false);
        restoreLooks(a); // colours, status icons and Do Not Disturb come back; notification service stops
        goHome(a);
        Toast.makeText(a, "mono is off. Open \"mono\" from your apps to turn it back on.",
                Toast.LENGTH_LONG).show();
    }

    static void goHome(Context c) {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tryStart(c, h, null);
    }
}
