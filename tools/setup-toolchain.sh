#!/usr/bin/env bash
# Fetches the Android build pieces this project needs when no Android SDK is installed.
#
# The Gradle build in app/build.gradle.kts does not use the Android Gradle Plugin. It
# compiles Kotlin against android.jar and drives aapt2 / D8 / zipalign / apksigner
# directly, so all it needs is:
#   - android.jar (API 34)       -> tools/cache/android-34.jar
#   - D8 (from R8 releases)      -> tools/cache/r8-<version>.jar
#   - aapt2, zipalign, apksigner -> from an installed SDK's build-tools, or Debian/Ubuntu packages
#
# If ANDROID_HOME points at a regular SDK with platforms;android-34 and build-tools
# installed, the build uses those instead and this script is not needed.
#
# API 34 rather than 35: the aapt2 shipped by Debian/Ubuntu cannot read the API 35
# resource table (it predates its compact entry encoding).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cache="$here/cache"
mkdir -p "$cache"

R8_VERSION=9.4.27
ANDROID_JAR_URL="https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar"
ANDROID_JAR_SHA256=6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad
R8_URL="https://storage.googleapis.com/r8-releases/raw/${R8_VERSION}/r8lib.jar"
R8_SHA256=6646aacebba0e8d13b2309b3b9191d08a2dbd187b648fdc4f78a684bcddade18

fetch() { # url dest sha256
  local url="$1" dest="$2" sum="$3"
  if [ -f "$dest" ] && echo "$sum  $dest" | sha256sum -c --status; then
    return
  fi
  echo "Downloading $url"
  curl -fL --retry 3 -o "$dest.part" "$url"
  echo "$sum  $dest.part" | sha256sum -c --status || { echo "Checksum mismatch for $url" >&2; rm -f "$dest.part"; exit 1; }
  mv "$dest.part" "$dest"
}

fetch "$ANDROID_JAR_URL" "$cache/android-34.jar" "$ANDROID_JAR_SHA256"
fetch "$R8_URL" "$cache/r8-${R8_VERSION}.jar" "$R8_SHA256"

missing=()
command -v aapt2 >/dev/null || missing+=(aapt)
command -v zipalign >/dev/null || missing+=(zipalign)
command -v apksigner >/dev/null || missing+=(apksigner)
if [ ${#missing[@]} -gt 0 ]; then
  echo "Installing ${missing[*]} from apt"
  sudo_cmd=""
  [ "$(id -u)" -ne 0 ] && sudo_cmd="sudo"
  $sudo_cmd apt-get install -y --no-install-recommends "${missing[@]}"
fi

echo "Toolchain ready in $cache"
