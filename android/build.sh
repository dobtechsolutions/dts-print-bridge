#!/usr/bin/env bash
# Builds dist/DTSPrintBridge.apk without Gradle (no Maven Central needed):
#   aapt2 compile/link -> javac (against android.jar) -> d8 -> zipalign -> apksigner
#
# Requirements: JDK 17+ (javac, keytool), zip, and an Android SDK with
#   platforms;android-34 and build-tools;36.1.0
#   (build-tools 34.x d8 crashes on JDK 21 class files: MethodParameters NPE - use 36.1.0+)
# One-time SDK setup (only dl.google.com is needed):
#   curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
#   mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" && unzip commandlinetools-linux-*.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"
#   mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
#   yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses
#   "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" "platforms;android-34" "build-tools;36.1.0"
#
# Signing: release keystore + password file live OUTSIDE the repo. Create once with:
#   keytool -genkeypair -keystore dts-print-bridge.jks -alias dtsprintbridge -keyalg RSA -keysize 2048 \
#     -validity 10000 -dname "CN=DOB Tech Solutions, O=DOB Tech Solutions, L=Rotterdam, C=NL"
#   (put the store password in dts-print-bridge.jks.pass next to it)
# IMPORTANT: every future update must be signed with the SAME keystore, or tablets refuse to update.
#
# Usage:  ANDROID_SDK_ROOT=... KEYSTORE=... KEYSTORE_PASS_FILE=... ./build.sh      (all optional)
#         ./build.sh test     # run the JVM protocol tests only
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRATCH="/tmp/claude-0/-home-claude/99dadd3d-7b8f-59d9-bac4-a03fd47c1155/scratchpad"
SDK="${ANDROID_SDK_ROOT:-$SCRATCH/android-sdk}"
BUILD_TOOLS="$SDK/build-tools/${BUILD_TOOLS_VERSION:-36.1.0}"
ANDROID_JAR="$SDK/platforms/android-${COMPILE_SDK:-34}/android.jar"
KEYSTORE="${KEYSTORE:-$SCRATCH/android-keystore/dts-print-bridge.jks}"
KEYSTORE_PASS_FILE="${KEYSTORE_PASS_FILE:-$KEYSTORE.pass}"
KEY_ALIAS="${KEY_ALIAS:-dtsprintbridge}"

MIN_SDK=24
TARGET_SDK=34
VERSION_CODE=1
VERSION_NAME=0.1.0

SRC="$HERE/app/src/main"
OUT="$HERE/build"
DIST="$HERE/dist"

run_tests() {
  echo "==> JVM protocol tests"
  rm -rf "$OUT/test-classes" && mkdir -p "$OUT/test-classes"
  javac -encoding UTF-8 --release 11 -d "$OUT/test-classes" \
    "$SRC"/java/com/dobtechsolutions/printbridge/core/*.java \
    "$HERE"/test/java/com/dobtechsolutions/printbridge/core/*.java
  java -cp "$OUT/test-classes" com.dobtechsolutions.printbridge.core.ProtocolTest
}

if [[ "${1:-}" == "test" ]]; then run_tests; exit 0; fi

for f in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner" "$ANDROID_JAR"; do
  [[ -e "$f" ]] || { echo "missing $f - see SDK setup at the top of this script" >&2; exit 1; }
done
[[ -f "$KEYSTORE" && -f "$KEYSTORE_PASS_FILE" ]] || { echo "missing keystore $KEYSTORE or $KEYSTORE_PASS_FILE" >&2; exit 1; }

run_tests

rm -rf "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT"/*.apk
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "==> aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir "$SRC/res" -o "$OUT/res/resources.zip"

echo "==> aapt2 link"
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$OUT/res/resources.zip"

echo "==> javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> package"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unaligned.apk" classes.dex)

echo "==> zipalign"
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "==> apksigner"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "file:$KEYSTORE_PASS_FILE" \
  --out "$DIST/DTSPrintBridge.apk" "$OUT/aligned.apk"
rm -f "$DIST/DTSPrintBridge.apk.idsig"

echo "==> verify"
"$BUILD_TOOLS/apksigner" verify --verbose "$DIST/DTSPrintBridge.apk" | head -6
"$BUILD_TOOLS/aapt2" dump badging "$DIST/DTSPrintBridge.apk" | grep -E "^(package|minSdkVersion|sdkVersion|targetSdkVersion)"
ls -l "$DIST/DTSPrintBridge.apk"
