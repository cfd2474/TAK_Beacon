#!/usr/bin/env bash
# Build the plugin for one ATAK line, against that line's SDK.
#
#   scripts/build.sh 5.8.0              # civ release
#   scripts/build.sh 5.6.0 debug        # civ debug
#   scripts/build.sh all                # civ release for every supported line
#
# SDKs are found as $ATAK_SDK_ROOT/ATAK-CIV-<x.y.z> (default ~/atak-sdk), the
# layout the atak-plugin-pipeline scripts use.
set -euo pipefail

LINES="5.6.0 5.7.0 5.8.0"
atak="${1:?usage: build.sh <atak x.y.z|all> [release|debug]}"
type="${2:-release}"
cd "$(dirname "$0")/.."

if [ "$atak" = all ]; then
    for l in $LINES; do "$0" "$l" "$type"; done
    exit 0
fi

root="${ATAK_SDK_ROOT:-$HOME/atak-sdk}"
sdk="$root/ATAK-CIV-$atak"
[ -f "$sdk/main.jar" ] || { echo "no SDK for $atak at $sdk" >&2; exit 1; }

Type="$(tr '[:lower:]' '[:upper:]' <<<"${type:0:1}")${type:1}"
./gradlew --no-daemon -q \
    -Patak.version="$atak" \
    -Psdk.path="$sdk" \
    -Ptakdev.plugin="$sdk/atak-gradle-takdev.jar" \
    clean "assembleCiv$Type"

mkdir -p dist
cp app/build/outputs/apk/civ/"$type"/*.apk dist/
ls dist/*"$atak"*"$type".apk
