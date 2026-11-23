#!/usr/bin/env bash
# Builds mono.apk with the plain Android SDK tools - no Gradle, no Android Studio.
# Linux / macOS (and Git Bash on Windows). On Windows PowerShell use build.ps1.
#
#   ./build.sh             build  -> build/mono.apk
#   ./build.sh --install   build, then install on the USB-connected phone (adb)
#
# Needs: Android SDK (platforms + build-tools + platform-tools) and a JDK 17+.
# The signing key is created on first build in keystore/ (git-ignored). Keep a backup:
# Android only installs updates signed with the same key.
set -euo pipefail
cd "$(dirname "$0")"

INSTALL=0
[ "${1:-}" = "--install" ] && INSTALL=1

# Paths handed to the SDK tools: native form (C:\... under Git Bash, unchanged elsewhere).
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

# ---------------------------------------------------------------- find tools
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "${LOCALAPPDATA:-/nonexistent}/Android/Sdk"; do
    if [ -d "$c" ]; then SDK="$c"; break; fi
  done
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then echo "Android SDK not found (set ANDROID_HOME)" >&2; exit 1; fi
SDK="$(cd "$SDK" && pwd)"

PLATFORM="$SDK/platforms/android-36"
if [ ! -f "$PLATFORM/android.jar" ]; then
  PLATFORM="$(ls -d "$SDK"/platforms/*/ | sort -V | tail -1)"
  PLATFORM="${PLATFORM%/}"
fi
ANDROID_JAR="$(native "$PLATFORM/android.jar")"

BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
BT="${BT%/}"
tool() {
  for ext in "" .exe .bat; do
    if [ -f "$BT/$1$ext" ]; then printf '%s' "$BT/$1$ext"; return 0; fi
  done
  echo "missing $1 in $BT" >&2
  return 1
}
AAPT2="$(tool aapt2)"
D8="$(tool d8)"
ZIPALIGN="$(tool zipalign)"
APKSIGNER="$(tool apksigner)"

JDK_BIN=""
if [ -n "${JAVA_HOME:-}" ] && { [ -e "$JAVA_HOME/bin/javac" ] || [ -e "$JAVA_HOME/bin/javac.exe" ]; }; then
  JDK_BIN="$JAVA_HOME/bin"
elif command -v javac >/dev/null 2>&1; then
  JDK_BIN="$(dirname "$(command -v javac)")"
else
  for j in "$HOME"/.jdks/*/bin; do
    if [ -e "$j/javac" ] || [ -e "$j/javac.exe" ]; then JDK_BIN="$j"; fi
  done
fi
if [ -z "$JDK_BIN" ]; then echo "No JDK found (set JAVA_HOME)" >&2; exit 1; fi
export PATH="$JDK_BIN:$PATH"
export JAVA_HOME="$(native "$(dirname "$JDK_BIN")")"

echo "sdk      $SDK"
echo "platform $(basename "$PLATFORM")   build-tools $(basename "$BT")"
echo "jdk      $JAVA_HOME"

# --------------------------------------------------------------------- build
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen"

echo "[1/6] resources"
"$AAPT2" compile --dir res -o "$OUT/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
  --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"

echo "[2/6] java"
SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find src "$OUT/gen" -name '*.java')
javac --release 11 -encoding UTF-8 -Xlint:-options -classpath "$ANDROID_JAR" -d "$OUT/classes" "${SOURCES[@]}"

echo "[3/6] dex"
jar --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --release --min-api 29 --lib "$ANDROID_JAR" --output "$OUT" "$OUT/classes.jar"

echo "[4/6] package"
jar --update --no-manifest --file "$OUT/unsigned.apk" -C "$OUT" classes.dex
"$ZIPALIGN" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/6] sign"
KS=keystore/mono.keystore
PW=keystore/password.txt
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  (
    umask 077
    # 24 characters from the OS's secure random source.
    { LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9' < /dev/urandom || true; } | head -c 24 > "$PW"
  )
  # Passwords are read from the file, never put on the command line.
  keytool -genkeypair -keystore "$KS" -alias mono -keyalg RSA -keysize 3072 -validity 10000 \
    -storepass:file "$PW" -keypass:file "$PW" -dname CN=mono
  echo "      new signing key: $KS  (back it up)"
fi
# One password protects both the keystore and the key (PKCS12); apksigner reuses it for the key.
"$APKSIGNER" sign --ks "$KS" --ks-key-alias mono --ks-pass "file:$(native "$PW")" \
  --out "$OUT/mono.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/mono.apk"

echo "[6/6] done: $OUT/mono.apk ($(( $(wc -c < "$OUT/mono.apk") / 1024 )) KB)"

if [ "$INSTALL" = 1 ]; then
  echo "installing on phone..."
  "$SDK/platform-tools/adb" install -r "$OUT/mono.apk"
fi
