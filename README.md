# mono

A black-and-white, text-only Android home screen in JetBrains Mono, styled after a Sway/Waybar/Wofi desktop.

```
 14:32 · tue 24 nov                     bat 87%

   whatsapp business
   chrome
   camera
   phone

 > search_                                  ···
```

## What it does

**Home**
- **Status line** (like Waybar): time · date on the left, battery on the right. Tap the time for alarms and the battery for battery settings. Optionally a **big clock** instead, or no clock.
- **Pinned apps** as plain text, no icons. Hold one to move it up or down, rename it, unpin it, or open app info.
- **System status bar hidden** on mono's screen by default; swipe down from the top edge to see it. Other apps keep theirs.
- **System look while mono is on** (optional, needs a one-time PC grant, see below). Turning mono off restores both exactly:
  - **black & white**: the whole phone is grayscale.
  - **quiet system icons**: the status bar in every app drops alarm, mute, Bluetooth, hotspot, location, NFC and similar icons, keeping time, signal and battery.
- **mono-style pop-ups**: menus and dialogs are pure black with a thin border and JetBrains Mono text, not Android's default grey boxes.

**Gestures**
| Gesture | Action |
|---|---|
| swipe up | all apps + search |
| swipe down | pull down notifications |
| swipe left | open an app you choose (default: camera) |
| swipe right | open an app you choose (default: phone) |

**Search drawer** (like Wofi)
- Type to filter. The best match is marked `>`; press Enter to open it.
- **Auto-open**: when only one app matches, it opens straight away (can be turned off).
- Matches by name start, **initials** (`gm` → google maps), word start, then anywhere. Within each group the apps you use most come first.
- **search web: …** row at the bottom sends the text to your browser's search.
- Hold an app to pin it, rename it, hide it, or open app info. Hidden apps leave the list but are still found by search.
- Fast-scroll thumb for the full list. The list can be sorted **a–z** or **most used**.

**`···` menu**: all apps · settings · hidden apps · home app settings · turn off mono.

**Settings**: status bar (hidden/shown) · black & white (on/off) · system icons (quiet/normal) · clock (line/big/off) · text size (small/medium/large) · align (left/center/right) · auto-open match · app list order · swipe left/right app.

## Safe by design

- **One permission**: `EXPAND_STATUS_BAR` (a normal permission, granted automatically). It lets a swipe down pull open the notification shade and nothing else.
- **Optional**: `WRITE_SECURE_SETTINGS`, for the system look only. Android never grants it on install; only a PC can:
  ```
  adb shell pm grant com.nilupul.mono android.permission.WRITE_SECURE_SETTINGS
  ```
  On ColorOS, first turn on Developer options → *Disable permission monitoring* and **restart the phone**; replugging USB is not enough. You can turn that option off again afterwards, and the grant stays.

  mono touches exactly two system settings with it: grayscale colour correction (`accessibility_display_daltonizer*`) and the status bar's hidden-icons list (`icon_blacklist`). It saves each previous value before the first change and restores it when mono is turned off. Without the grant, switch grayscale by hand: Accessibility → Color correction → Grayscale.
- No internet, no services, no background work, no broadcast receivers in the manifest. Launch counts for "most used" stay on the phone.
- **Installing changes nothing.** The home screen ships disabled (`android:enabled="false"`).
- **Easy on/off**: open **mono** from the app list and tap **turn on** or **turn off**. Off disables mono's home-screen component, so Android falls straight back to the OPPO launcher. You can also turn it off from `···` → *turn off mono*.
- **Remove completely**: Settings → Apps → mono → Uninstall, or `adb uninstall com.nilupul.mono` from a PC.

### Deliberately left out

These are common in other minimal launchers but need deep system access, so mono doesn't have them:

| Feature | Would need |
|---|---|
| double-tap to lock | Accessibility service (can read and control other apps) |
| unread-notification highlights, now playing | Notification access |
| screen time on home | Usage access |
| contact search | Contacts permission |
| system-wide JetBrains Mono font | root (ColorOS only allows Theme Store fonts; the zFont "Myanmar region" trick is unconfirmed on ColorOS 12) |

## Build

No Gradle and no Android Studio. `build.ps1` calls the Android SDK tools directly (aapt2 → javac → d8 → zipalign → apksigner).

Requires the Android SDK (platforms, build-tools, platform-tools) and JDK 17 or newer.

```powershell
.\build.ps1            # -> build\mono.apk  (~160 KB)
.\build.ps1 -Install   # build + adb install -r on the USB-connected phone
```

The first build creates a signing key in `keystore\`, which is git-ignored. **Back it up.** Android only accepts updates signed with the same key; without it you'd have to uninstall, which loses your pins and renames.

## Layout

```
AndroidManifest.xml          one permission; HomeActivity disabled by default
src/com/nilupul/mono/
  Apps.java                  app list, pins/hidden/renames/usage, font, on/off logic
  HomeActivity.java          home screen, gestures, search drawer, settings
  SwitchActivity.java        the "mono" on/off screen in the app list
  Ui.java                    mono-style pop-ups (lists, confirms, text input)
res/                         black theme, ">_" icon
assets/                      JetBrains Mono Regular + its OFL licence
build.ps1                    build script
```

Targets Android 10+ (minSdk 29, targetSdk 34). Built for an OPPO A17 (ColorOS 12, 4 GB RAM).

Features were chosen by comparing Olauncher, mLauncher, Last Launcher, MinimalSF, Slate and YAM Launcher, keeping only behaviour that needs no special access.

JetBrains Mono is © JetBrains, licensed under the SIL Open Font License 1.1 (`assets/licenses/JetBrainsMono-OFL.txt`).
