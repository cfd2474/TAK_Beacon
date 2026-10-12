#!/usr/bin/env bash
# Build the tak.gov source-submission zip for each ATAK target.
#
#   scripts/submit.sh                    # 5.6.0, 5.7.0 and 5.8.0
#   scripts/submit.sh 5.8.0              # one target
#   scripts/submit.sh --dry-run 5.8.0    # allow a test-signed app; never submit the result
#
# Every gate in the atak-plugin-pipeline skill's submission-zip.sh runs on the
# plugin's own source. The embedded TAK Beacon app is the one binary in the
# submission and the scrub rightly refuses .apk files, so it is held out while
# those gates run and added afterwards, only after this script has verified it
# is com.taksolutions.takbeacon signed by Google Play's app signing key
# (TAKBEACON_PLAY_SIGNER, the SHA-256 from Play Console, App integrity). The
# finished zip, APK included, is then built once more from a clean extract.
#
# Needs: ~/atak-sdk/ATAK-CIV-<x.y.z> per target, the skill at
# ~/.claude/skills/atak-plugin-pipeline, zip, and typst 0.13.1 on PATH to check
# the manual.
set -euo pipefail

# Google Play app signing key for com.taksolutions.takbeacon (Play Console,
# Test and release, App integrity). A public certificate hash, not a secret.
TAKBEACON_PLAY_SIGNER="${TAKBEACON_PLAY_SIGNER:-1D:62:9D:38:59:A1:E3:A8:E7:29:CE:3E:8A:E7:88:B6:88:BA:18:34:B3:EB:76:30:B8:8F:BC:BC:E5:D2:1C:42}"

DRY=0
if [ "${1:-}" = "--dry-run" ]; then DRY=1; shift; fi
TARGETS="${*:-5.6.0 5.7.0 5.8.0}"

HERE="$(cd "$(dirname "$0")/.." && pwd)"
# On Windows (Git Bash) Java cannot read /c/... paths, and the skill's gate
# writes the SDK paths it is given into local.properties: hand it C:/... ones.
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
export ATAK_SDK_ROOT="$(winpath "${ATAK_SDK_ROOT:-$HOME/atak-sdk}")"
# The skill's gate keeps a directory in a shell variable named TMP, which on
# Windows is also the variable Java takes its temp dir from; pin Java's.
if command -v cygpath >/dev/null 2>&1; then
    JTMP="$(mktemp -d)"
    export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$(cygpath -m "$JTMP") ${JAVA_TOOL_OPTIONS:-}"
fi
SKILL="${ATAK_PIPELINE_SKILL:-$HOME/.claude/skills/atak-plugin-pipeline}"
ASSET="$HERE/app/src/main/assets/takbeacon.apk"
DIST="${ATAK_DIST:-$HOME/atak-dist}"
NAME=TAKBeacon

[ -f "$ASSET" ] || { echo "no app at $ASSET: stage it with scripts/stage-app.sh" >&2; exit 1; }

# ---- the embedded app ---------------------------------------------------------
export ANDROID_HOME="$(winpath "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}")"
bt="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
first() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return 0; }; done; return 1; }
aapt="$(first "$bt/aapt" "$bt/aapt.exe")"
apksigner="$(first "$bt/apksigner" "$bt/apksigner.bat")"
pkg="$("$aapt" dump badging "$ASSET" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
[ "$pkg" = "com.taksolutions.takbeacon" ] || { echo "embedded APK is '$pkg', not the TAK Beacon app" >&2; exit 1; }
signer="$("$apksigner" verify --print-certs "$ASSET" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr -d '\r')"
want="$(tr '[:upper:]' '[:lower:]' <<<"${TAKBEACON_PLAY_SIGNER:-}" | tr -d ':')"
if [ -z "$want" ] || [ "$signer" != "$want" ]; then
    if [ "$DRY" = 1 ]; then
        echo "WARNING: embedded app signer $signer is not the Play signer; DRY RUN, do not submit" >&2
    else
        echo "embedded app signer $signer is not TAKBEACON_PLAY_SIGNER (${want:-unset}); refusing" >&2
        exit 1
    fi
fi
apk_sha="$(sha256sum "$ASSET" | cut -d' ' -f1)"

# ---- per target -------------------------------------------------------------------
GRADLE="$HERE/app/build.gradle"
cp "$GRADLE" "$GRADLE.orig"
HOLD="$(mktemp -d)"
restore() {
    [ -f "$HOLD/takbeacon.apk" ] && mv "$HOLD/takbeacon.apk" "$ASSET"
    [ -f "$GRADLE.orig" ] && mv "$GRADLE.orig" "$GRADLE"
    rm -rf "$HOLD"
}
trap restore EXIT

for atak in $TARGETS; do
    sdk="$ATAK_SDK_ROOT/ATAK-CIV-$atak"
    [ -f "$sdk/main.jar" ] || { echo "no SDK for $atak at $sdk" >&2; exit 1; }
    echo "==================== ATAK $atak ===================="
    sed -i "s/^\(\s*ext\.ATAK_VERSION\s*=\s*\)'[^']*'/\1'$atak'/" "$GRADLE"

    mv "$ASSET" "$HOLD/takbeacon.apk"
    ATAK_SDK="$sdk" bash "$SKILL/scripts/submission-zip.sh" "$NAME"
    mv "$HOLD/takbeacon.apk" "$ASSET"

    zipfile="$(ls -t "$DIST/$NAME"-*-"$atak".zip | head -1)"
    stage="$(mktemp -d)"
    mkdir -p "$stage/$NAME/app/src/main/assets"
    cp "$ASSET" "$stage/$NAME/app/src/main/assets/takbeacon.apk"
    cp "$HERE/LICENSE" "$HERE/NOTICE.md" "$stage/$NAME/"
    ( cd "$stage" && zip -q "$zipfile" "$NAME/app/src/main/assets/takbeacon.apk" "$NAME/LICENSE" "$NAME/NOTICE.md" )
    rm -rf "$stage"

    echo "==> final zip, app included: clean-extract build"
    tmp="$(mktemp -d)"
    unzip -q "$zipfile" -d "$tmp"
    got="$(sha256sum "$tmp/$NAME/app/src/main/assets/takbeacon.apk" | cut -d' ' -f1)"
    [ "$got" = "$apk_sha" ] || { echo "  FAIL  embedded app changed in the zip" >&2; exit 1; }
    printf 'sdk.dir=%s\nsdk.path=%s\ntakdev.plugin=%s/atak-gradle-takdev.jar\n' \
        "$ANDROID_HOME" "$sdk" "$sdk" > "$tmp/$NAME/local.properties"
    ( cd "$tmp/$NAME" && ./gradlew --no-daemon -q assembleCivRelease >/dev/null )
    built="$(ls "$tmp/$NAME"/app/build/outputs/apk/civ/release/*.apk)"
    unzip -l "$built" | grep -q "assets/takbeacon.apk" || { echo "  FAIL  built plugin lacks the app" >&2; exit 1; }
    rm -rf "$tmp"
    size=$(wc -c < "$zipfile" | tr -d ' ')
    [ "$size" -lt 16000000 ] || { echo "  FAIL  zip is $size bytes" >&2; exit 1; }
    echo "  PASS  $zipfile ($((size / 1024)) KB), app sha256 $apk_sha"
    if [ "$DRY" = 1 ]; then mv "$zipfile" "${zipfile%.zip}-DRYRUN-DO-NOT-SUBMIT.zip"; fi
done
