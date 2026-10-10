#!/usr/bin/env bash
# Downloads the build tools into tools/cache (Linux; Debian/Ubuntu for the apt part).
# Gradle / Android Gradle Plugin are NOT needed: the app uses only the Android framework.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p cache && cd cache

# 1. Kotlin compiler 2.0.21
if [ ! -x kotlinc/bin/kotlinc ]; then
  curl -fsSL -o kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip
  unzip -q kotlinc.zip && rm kotlinc.zip
fi
# 2. D8 dexer (part of R8) 8.5.35
[ -f r8lib.jar ] || curl -fsSL -o r8lib.jar https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar
# 3. Android 14 (API 34) framework classes to compile against
[ -f android-34.jar ] || curl -fsSL -o android-34.jar https://github.com/Reginer/aosp-android-jar/raw/main/android-34/android.jar
# 4. SQLite JDBC driver (only for running the tests on a PC)
[ -f sqlite-jdbc.jar ] || curl -fsSL -o sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar

# 5. aapt, zipalign, apksigner, and an Android platform jar for aapt
if ! command -v aapt >/dev/null || ! command -v apksigner >/dev/null || [ ! -f /usr/lib/android-sdk/platforms/android-23/android.jar ]; then
  echo "Installing aapt, zipalign, apksigner (needs sudo)..."
  sudo apt-get update && sudo apt-get install -y aapt zipalign apksigner android-sdk-platform-23 openjdk-17-jdk-headless unzip
fi
echo "Tools ready in $(pwd)"
