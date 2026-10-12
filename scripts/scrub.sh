#!/usr/bin/env bash
# Publish scrub for this repository: the atak-plugin-pipeline skill's
# publish-scrub.sh, unchanged, with exactly one accepted literal.
#
#   scripts/scrub.sh            # exit 0 = safe to push
#
# Accepted: 239.2.3.1, ATAK's standard situational-awareness multicast group,
# which TAK Beacon's UDP broadcast uses by default. It is a public, well-known
# multicast group, not a host or anyone's network. An ip-address finding is
# accepted only when its line holds no other address once that literal is
# removed (re-tested with the skill's own pattern). Every other finding fails.
set -uo pipefail

SKILL="${ATAK_PIPELINE_SKILL:-$HOME/.claude/skills/atak-plugin-pipeline}"
ACCEPT='239\.2\.3\.1'
IP_RE='(^|[^0-9.])(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|([0-9]{1,3}\.){3}(1[0-9]{2}|2[0-5][0-9])|(1[0-9]{2}|2[0-5][0-9])(\.[0-9]{1,3}){3})([^0-9.]|$)'

out="$(bash "$SKILL/scripts/publish-scrub.sh" "$@" 2>&1)"
status=$?
if [ $status -eq 0 ]; then
    echo "$out" | tail -1
    exit 0
fi

rejected=0
accepted=0
while IFS= read -r line; do
    case "$line" in
        "  ["*"] "*) ;;
        *) continue ;;
    esac
    if [[ "$line" == "  [ip-address] "* ]]; then
        rest="$(printf '%s' "$line" | sed -E "s/^  \[ip-address\] [^:]+:[0-9]+://; s/$ACCEPT//g")"
        if ! printf '%s' "$rest" | grep -qE "$IP_RE"; then
            accepted=$((accepted + 1))
            continue
        fi
    fi
    echo "$line"
    rejected=$((rejected + 1))
done <<< "$out"

if [ $rejected -gt 0 ]; then
    echo "scrub: FAIL ($rejected finding(s) above; $accepted accepted as 239.2.3.1)"
    exit 1
fi
if [ $accepted -eq 0 ]; then
    # The skill failed for a reason that produced no finding lines: show it.
    echo "$out"
    exit 1
fi
echo "scrub: PASS ($accepted occurrence(s) of ATAK's SA multicast 239.2.3.1 accepted; nothing else found)"
