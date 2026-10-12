#!/usr/bin/env bash
# Put a TAK Beacon app APK into the plugin's assets, where AppInstaller finds it.
#
#   scripts/stage-app.sh ~/atak-dist/takbeacon-app/TAKBeacon-1.0.0-play-signed.apk
#   scripts/stage-app.sh <apk> --allow-test-signer     # local testing only
#
# For a release the APK must be the Play-signed universal APK, byte for byte
# (docs/play-release.md in the repository root): anything else is flagged by
# Play Protect and cannot update an app installed from Play. Set
# TAKBEACON_PLAY_SIGNER to the SHA-256 of the app signing key shown in Play
# Console (Test and release, App integrity) and this script refuses any other
# signer unless --allow-test-signer is given.
set -euo pipefail

apk="${1:?usage: stage-app.sh <apk> [--allow-test-signer]}"
allow_test="${2:-}"
cd "$(dirname "$0")/.."
dest="app/src/main/assets/takbeacon.apk"

ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
bt="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
first() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return 0; }; done; return 1; }
aapt="$(first "$bt/aapt" "$bt/aapt.exe")"
apksigner="$(first "$bt/apksigner" "$bt/apksigner.bat")"

pkg="$("$aapt" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
ver="$("$aapt" dump badging "$apk" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")"
[ "$pkg" = "com.taksolutions.takbeacon" ] || { echo "not the TAK Beacon app: package '$pkg'" >&2; exit 1; }

signer="$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr -d '\r')"
[ -n "$signer" ] || { echo "APK is not signed" >&2; exit 1; }
if [ -n "${TAKBEACON_PLAY_SIGNER:-}" ] && [ "$signer" != "$(tr '[:upper:]' '[:lower:]' <<<"${TAKBEACON_PLAY_SIGNER//:/}")" ]; then
    if [ "$allow_test" != "--allow-test-signer" ]; then
        echo "signer $signer is not the Play app signing key; refusing (see docs/play-release.md)" >&2
        exit 1
    fi
    echo "WARNING: staging a test-signed app. Never submit this build." >&2
fi

mkdir -p "$(dirname "$dest")"
cp "$apk" "$dest"
echo "staged $pkg $ver"
echo "  signer  $signer"
echo "  sha256  $(sha256sum "$dest" | cut -d' ' -f1)"
