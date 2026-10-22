#!/usr/bin/env bash
# Build the on-TV checks after ./build.sh, using the same SDK and signing key.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found (see README)."; exit 1; }
[ -d out/classes ] || { echo "Run ./build.sh first."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
rm -rf out/tests && mkdir -p out/tests/classes
"$BT/aapt2" link -o out/tests/base.apk -I "$AJ" --manifest tests/AndroidManifest.xml --min-sdk-version 28 --target-sdk-version 33
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" \
  -classpath "$AJ:out/classes" -d out/tests/classes tests/RecommendationsTest.java
"$BT/d8" --release --min-api 28 --lib "$AJ" --classpath out/classes --output out/tests $(find out/tests/classes -name '*.class')
(cd out/tests && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/tests/base.apk out/tests/aligned.apk
"$BT/apksigner" sign --ks freemytv.keystore --ks-pass pass:freemytv --key-pass pass:freemytv \
  --out out/tests/checks.apk out/tests/aligned.apk
echo 'Built out/tests/checks.apk. Install it, then run: adb shell am instrument -w io.freemytv.tests/io.freemytv.RecommendationsTest'
