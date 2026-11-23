# Security

## Reporting a problem

Please report security or privacy issues privately through GitHub: **Security → Report a vulnerability** on this repository. Don't open a public issue for them.

## What mono can and cannot do

| Capability | Status |
|---|---|
| Internet access | **None.** No `INTERNET` permission, so nothing can leave the phone. |
| Background work | None. No services, no manifest broadcast receivers, no scheduled jobs. |
| Data it stores | Your pins, renames, hidden apps, settings and app-launch counts, in the app's private storage only. `allowBackup="false"`, so they are not copied to cloud or adb backups. |
| `EXPAND_STATUS_BAR` | Normal permission, granted automatically. Only used to pull down the notification shade on swipe-down. |
| `WRITE_SECURE_SETTINGS` | **Optional**, and never granted on install; only you can grant it, from a PC with `adb`. mono writes exactly two fixed sets of values with it: grayscale colour correction and the status bar hidden-icons list. It saves each previous value first and restores it when mono is turned off. It never writes anything taken from another app or from input. |
| Notification access (`NotifyService`) | **Optional**, off by default; you allow it in Android settings. Reads only each notification's package name and flags (to count, and for focus mode to remove notifications from apps you chose). Never reads titles, text or extras; keeps counts in memory only. Paused (`requestUnbind`) whenever mono is off or no notification feature is on. Only the system can bind it. |
| `ACCESS_NOTIFICATION_POLICY` | **Optional** "quiet": switches Do Not Disturb to priority-only after you allow Do Not Disturb access, and restores the previous mode. Cannot read notifications. |
| Exported components | `HomeActivity` (required for a home screen) and `SwitchActivity` (the app-list icon). Neither reads data from the intents that start them. The on/off button ignores taps while another app draws over it (tapjacking protection). |
| Web search | Only when you tap **search web** or press Enter with no app matching. The typed text goes to your browser's search. |

## Known limitation

Android gives an app no warning before it is uninstalled. If you uninstall mono while it is **on** and you had granted the optional permission, grayscale and the hidden status-bar icons stay as mono left them. **Turn mono off before uninstalling.** To undo by hand:

- Settings → Accessibility → Color correction → off
- From a PC: `adb shell settings delete secure icon_blacklist`

## Release signing

Release APKs are signed with the author's own key (APK Signature Scheme v3). The key is never committed; `keystore/` is git-ignored.
