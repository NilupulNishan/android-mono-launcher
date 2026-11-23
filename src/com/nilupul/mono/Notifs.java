package com.nilupul.mono;

import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What mono knows about notifications: per app, how many there are, and how many
 * focus mode removed. Nothing else: no titles, no message text. Kept in memory only.
 */
final class Notifs {

    interface Listener {
        void changed();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Map<String, Integer> counts = Collections.emptyMap();
    private static final Map<String, Integer> hidden = new HashMap<>();
    private static boolean connected;
    private static Listener listener;

    private Notifs() {}

    static synchronized void connected(boolean on) {
        connected = on;
        if (!on) counts = Collections.emptyMap();
        post();
    }

    static synchronized boolean isConnected() {
        return connected;
    }

    static synchronized void update(Map<String, Integer> newCounts) {
        counts = Collections.unmodifiableMap(new HashMap<>(newCounts));
        post();
    }

    static synchronized int count(String pkg) {
        Integer n = counts.get(pkg);
        return n == null ? 0 : n;
    }

    static synchronized Map<String, Integer> counts() {
        return counts;
    }

    static synchronized void addHidden(String pkg) {
        Integer n = hidden.get(pkg);
        hidden.put(pkg, n == null ? 1 : n + 1);
        post();
    }

    static synchronized Map<String, Integer> hidden() {
        return new HashMap<>(hidden);
    }

    static synchronized void clearHidden(String pkg) {
        if (pkg == null) hidden.clear();
        else hidden.remove(pkg);
        post();
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }

    private static void post() {
        MAIN.post(() -> {
            Listener l;
            synchronized (Notifs.class) {
                l = listener;
            }
            if (l != null) l.changed();
        });
    }
}
