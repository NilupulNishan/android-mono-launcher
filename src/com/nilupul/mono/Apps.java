package com.nilupul.mono;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
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
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** App list, saved choices (pins, hidden, renames), styling and the on/off switch. */
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

        App(String pkg, String cls, String original, String label) {
            this.pkg = pkg;
            this.cls = cls;
            this.original = original;
            this.label = label;
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
        tryStart(c, i, "can't open " + a.label);
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

    /** First run: pin phone, messages, WhatsApp, Chrome, camera, gallery and settings if present. */
    static void seedPins(Context c, List<App> all) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("seeded", false)) return;

        List<String> wanted = new ArrayList<>();
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            if (tm != null) wanted.add(tm.getDefaultDialerPackage());
        } catch (RuntimeException ignored) { }
        try {
            wanted.add(Telephony.Sms.getDefaultSmsPackage(c));
        } catch (RuntimeException ignored) { }
        wanted.add("com.whatsapp.w4b");
        wanted.add("com.whatsapp");
        wanted.add("com.android.chrome");
        ResolveInfo cam = c.getPackageManager().resolveActivity(
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), PackageManager.MATCH_DEFAULT_ONLY);
        if (cam != null && cam.activityInfo != null) wanted.add(cam.activityInfo.packageName);
        wanted.add("com.coloros.gallery3d");
        wanted.add("com.android.settings");

        Map<String, App> firstByPkg = new LinkedHashMap<>();
        for (App a : all) if (!firstByPkg.containsKey(a.pkg)) firstByPkg.put(a.pkg, a);

        List<String> pins = new ArrayList<>();
        for (String pkg : wanted) {
            App a = pkg == null ? null : firstByPkg.get(pkg);
            if (a != null && !pins.contains(a.key())) pins.add(a.key());
        }
        savePins(c, pins);
        p.edit().putBoolean("seeded", true).apply();
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

    /** Turn mono off: remove its home screen, so Android goes back to the OPPO home screen. */
    static void turnOff(Activity a) {
        setHomeEnabled(a, false);
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
