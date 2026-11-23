package com.nilupul.mono;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional notification listener behind unread markers, the notification list and focus mode.
 *
 * Ships disabled; mono enables it only while mono is on and one of those features is turned on,
 * and Android only connects it after you allow "notification access" yourself.
 *
 * Privacy: it reads only each notification's package name and flags. It never reads the title,
 * text or any extras, never stores anything, and mono has no internet permission.
 */
public class NotifyService extends NotificationListenerService {

    /** The connected instance, so mono can pause it (requestUnbind) when it isn't needed. */
    static volatile NotifyService live;

    @Override
    public void onListenerConnected() {
        if (!Apps.listenerShouldRun(this)) {
            // e.g. connected by Android at boot while mono is off: pause straight away
            try {
                requestUnbind();
            } catch (RuntimeException ignored) { }
            return;
        }
        live = this;
        Notifs.connected(true);
        recount();
    }

    @Override
    public void onListenerDisconnected() {
        live = null;
        Notifs.connected(false);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (focusBlocks(sbn)) {
            try {
                cancelNotification(sbn.getKey());
                Notifs.addHidden(sbn.getPackageName());
            } catch (RuntimeException ignored) { }
        }
        recount();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        recount();
    }

    private void recount() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            return; // not connected (yet)
        }
        Map<String, Integer> counts = new HashMap<>();
        if (active != null) {
            for (StatusBarNotification s : active) {
                if (!countable(s)) continue;
                Integer n = counts.get(s.getPackageName());
                counts.put(s.getPackageName(), n == null ? 1 : n + 1);
            }
        }
        Notifs.update(counts);
    }

    /** Count only things you can clear: no music players, downloads, calls or group headers. */
    private static boolean countable(StatusBarNotification s) {
        if (s.isOngoing() || !s.isClearable()) return false;
        return (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    /** Focus mode: remove new notifications from the apps you picked, while mono is on. */
    private boolean focusBlocks(StatusBarNotification s) {
        if (!Apps.prefs(this).getBoolean(Apps.N_FOCUS, false)) return false;
        if (s.isOngoing() || !s.isClearable()) return false;
        String category = s.getNotification().category;
        if (Notification.CATEGORY_CALL.equals(category)
                || Notification.CATEGORY_ALARM.equals(category)) return false; // never block calls/alarms
        if (getPackageName().equals(s.getPackageName())) return false;
        return Apps.focusApps(this).contains(s.getPackageName()) && Apps.isOn(this);
    }
}
