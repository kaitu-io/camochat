#!/usr/bin/env bash
# Unit-style checks for lib-config.sh helpers (no network).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib-config.sh"
fail=0
t() { if "$@"; then :; else echo "FAIL: $*"; fail=1; fi; }
n() { if "$@"; then echo "FAIL (expected false): $*"; fail=1; fi; }
t version_gt 4 3
n version_gt 3 3
n version_gt 2 3
n version_gt "" 3
n version_gt 4 abc
[ "$(printf 'aGVsbG8' | b64url_decode)" = "hello" ] || { echo "FAIL b64url"; fail=1; }
[ "$(printf 'Pz8_' | b64url_decode)" = "???" ] || { echo "FAIL b64url urlsafe"; fail=1; }
# validate_payload cases (die exits, so run in subshells)
P="$CC_ROOT/release/config/payload.json"
tmpd="$(mktemp -d)"; trap 'rm -rf "$tmpd"' EXIT
vp() { (validate_payload "$1") >/dev/null 2>&1; }
t vp "$P"
jq '.android.minVersionCode = 99' "$P" > "$tmpd/a.json"; n vp "$tmpd/a.json"
jq '.shareSite = "http://x.example/"' "$P" > "$tmpd/b.json"; n vp "$tmpd/b.json"
jq '.relays[0] += "/"' "$P" > "$tmpd/c.json"; n vp "$tmpd/c.json"
jq '.sources[0] = "https://x.example/c"' "$P" > "$tmpd/d.json"; n vp "$tmpd/d.json"
jq '.android.latest.sha256 = "abc"' "$P" > "$tmpd/e.json"; n vp "$tmpd/e.json"
jq '.android.latest.mirrors = []' "$P" > "$tmpd/f.json"; n vp "$tmpd/f.json"
# install_local also refreshes the iOS factory copy; the reminder names it (paths redirected to tmp)
printf 'P' > "$tmpd/p.json"; printf 'E' > "$tmpd/e.env.json"
# shellcheck disable=SC2034  # consumed by the sourced helpers
(
  CC_PAYLOAD="$tmpd/out-payload.json" CC_ENVELOPE="$tmpd/out-env.json" CC_IOS_ENVELOPE="$tmpd/out-ios.json" DRY_RUN=0
  install_local "$tmpd/e.env.json" "$tmpd/p.json"
)
t cmp -s "$tmpd/out-ios.json" "$tmpd/e.env.json"
t cmp -s "$tmpd/out-env.json" "$tmpd/e.env.json"
t cmp -s "$tmpd/out-payload.json" "$tmpd/p.json"
# shellcheck disable=SC2034
reminder="$(DRY_RUN=0 CC_INVALIDATE_FAILED=0; print_commit_reminder)"
case "$reminder" in *ios/ChencangShared/Sources/ChencangShared/Resources/chencang-config.json*) ;; *) echo "FAIL: reminder lacks iOS copy"; fail=1 ;; esac
[ "$fail" = 0 ] && echo "ok"
exit "$fail"
