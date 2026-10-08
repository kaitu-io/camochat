#!/usr/bin/env bash
# Publish a hand-edited release/config/payload.json (relays/sources/shareSite...)
# without a new app version. The "android" segment must stay untouched.
# usage: publish-config.sh [--dry-run]
set -euo pipefail
# shellcheck source=lib-config.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib-config.sh"

# shellcheck disable=SC2034
DRY_RUN=0
for a in "$@"; do
  case "$a" in --dry-run) DRY_RUN=1 ;; *) die "usage: publish-config.sh [--dry-run]" ;; esac
done
cc_need jq aws curl cargo base64 diff

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fetch_online_envelope "$TMP/online-envelope.json"
envelope_payload "$TMP/online-envelope.json" > "$TMP/online.canon.json"
online_seq="$(jq -r .seq "$TMP/online.canon.json")"

jq -S 'del(.seq)' "$CC_PAYLOAD" > "$TMP/l.noseq"
jq -S 'del(.seq)' "$TMP/online.canon.json" > "$TMP/o.noseq"
cmp -s "$TMP/l.noseq" "$TMP/o.noseq" && die "payload.json has no change vs the online config (other than seq); nothing to publish"
[ "$(jq -S .android "$CC_PAYLOAD")" = "$(jq -S .android "$TMP/online.canon.json")" ] \
  || die "payload.json changes the 'android' segment; use publish-update.sh for that"

new_seq=$((online_seq + 1))
jq --argjson s "$new_seq" '.seq = $s' "$CC_PAYLOAD" > "$TMP/payload.new.json"
confirm_diff "$TMP/online.canon.json" <(jq -S . "$TMP/payload.new.json")
validate_payload "$TMP/payload.new.json"
sign_config "$TMP/payload.new.json" "$TMP/envelope.new.json"

upload_config "$TMP/envelope.new.json"
install_local "$TMP/envelope.new.json" "$TMP/payload.new.json"
invalidate_all '/c/*'
verify_online "$TMP/envelope.new.json" "$new_seq"
print_commit_reminder
