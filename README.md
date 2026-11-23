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

- **Status line** (like Waybar): time · date on the left, battery on the right. Tap the time for alarms and the battery for battery settings.
- **Pinned apps** shown as plain text, no icons.
- **Search drawer** (like Wofi): swipe up or tap `> search_`, type, and press Enter to open the best match.
- **Hold an app** to pin or unpin it, move it, rename it, hide it, or open app info. Hidden apps leave the list but still show up in search.
- **`···` menu**: all apps, hidden apps, home app settings, turn off mono.

## Safe by design

- **No permissions**: no internet, no services, no background work, no broadcast receivers in the manifest. It lists apps and opens them, nothing else.
- **Installing changes nothing.** The home screen ships disabled (`android:enabled="false"`).
- **Easy on/off**: open **mono** from the app list and tap **turn on** or **turn off**. Turning it off disables mono's home screen component, so Android falls straight back to the OPPO launcher. You can also turn it off from `···` → *turn off mono*.
- **Remove completely**: Settings → Apps → mono → Uninstall (or `adb uninstall com.nilupul.mono`).

If the phone is ever stuck on mono, run this from a PC:

```
adb shell pm disable-user --user 0 com.nilupul.mono
```

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
AndroidManifest.xml          no permissions; HomeActivity disabled by default
src/com/nilupul/mono/
  Apps.java                  app list, pins/hidden/renames, font, on/off logic
  HomeActivity.java          the home screen + search drawer
  SwitchActivity.java        the "mono" on/off screen in the app list
res/                         black theme, ">_" icon
assets/                      JetBrains Mono Regular + its OFL licence
build.ps1                    build script
```

Targets Android 10+ (minSdk 29, targetSdk 34). Built for an OPPO A17 (ColorOS 12, 4 GB RAM).

JetBrains Mono is © JetBrains, licensed under the SIL Open Font License 1.1 (`assets/licenses/JetBrainsMono-OFL.txt`).
