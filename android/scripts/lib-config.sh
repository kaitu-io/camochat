#!/usr/bin/env bash
# Shared helpers for publish-update.sh / publish-config.sh (sourced, not executed).
# Honors DRY_RUN=1: nothing is uploaded/invalidated/written into release/config.

CC_BUCKET="${CC_BUCKET:-chencang-site}"
CC_CONFIG_KEY="c/chencang-config.json"
CC_KEY_FILE="${CC_KEY_FILE:-$HOME/.chencang-keystore/config-signing.ed25519}"
# shellcheck disable=SC2034
CC_EXPECTED_CERT="02aedc63fbb7793cba5a313623548710aae94ac6e78cd3afcad39b166fa57006"
# H1 H2 H3 : "<distribution-id> <domain>"
CC_HOSTS=(
  "E2TRN129L7BYBK d3nnqcgewrb4f0.cloudfront.net"
  "E3MDHL18OSE5DL dzr6ldib1w492.cloudfront.net"
  "E3P8OZYVOPB9EJ dee2wx7h03hqb.cloudfront.net"
)
CC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CC_PAYLOAD="$CC_ROOT/release/config/payload.json"
CC_ENVELOPE="$CC_ROOT/release/config/chencang-config.json"
# iOS bundles its factory copy from here (Android reads release/config directly via assets.srcDir).
CC_IOS_ENVELOPE_REL="ios/ChencangShared/Sources/ChencangShared/Resources/chencang-config.json"
CC_IOS_ENVELOPE="${CC_IOS_ENVELOPE:-$CC_ROOT/$CC_IOS_ENVELOPE_REL}"
DRY_RUN="${DRY_RUN:-0}"

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }

cc_need() {
  local c
  for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "missing dependency: $c"; done
}

# base64url (no padding) -> stdout bytes
b64url_decode() {
  local s pad
  s="$(tr '_-' '/+')"
  pad=$(( (4 - ${#s} % 4) % 4 ))
  while [ "$pad" -gt 0 ]; do s="${s}="; pad=$((pad - 1)); done
  printf '%s' "$s" | { base64 -d 2>/dev/null || base64 -D; }
}

# Fetch the online envelope from H1 into $1.
fetch_online_envelope() {
  local out="$1" dom="${CC_HOSTS[0]#* }"
  curl -fsS --max-time 20 "https://$dom/$CC_CONFIG_KEY" -o "$out" \
    || die "cannot download online config from https://$dom/$CC_CONFIG_KEY"
}

# envelope file -> pretty payload JSON on stdout
envelope_payload() { jq -r .p "$1" | b64url_decode | jq -S .; }

# Abort unless local payload.json (canonical) == online payload. $1 = scratch dir.
assert_local_matches_online() {
  local tmp="$1"
  fetch_online_envelope "$tmp/online-envelope.json"
  envelope_payload "$tmp/online-envelope.json" > "$tmp/online-payload.canon.json" \
    || die "online envelope is not decodable"
  jq -S . "$CC_PAYLOAD" > "$tmp/local-payload.canon.json"
  if ! diff -q "$tmp/online-payload.canon.json" "$tmp/local-payload.canon.json" >/dev/null; then
    diff -u "$tmp/online-payload.canon.json" "$tmp/local-payload.canon.json" >&2 || true
    die "release/config/payload.json differs from the online payload. Sync it first (git pull / copy the online payload) and retry."
  fi
}

# version_gt NEW OLD : succeeds only if NEW > OLD (integers).
version_gt() {
  [[ "$1" =~ ^[0-9]+$ && "$2" =~ ^[0-9]+$ ]] || return 1
  [ "$1" -gt "$2" ]
}

# validate_payload FILE : enforce every rule the clients apply to a config payload.
validate_payload() {
  local errs
  errs="$(jq -r '
    def https: type == "string" and test("^https://[^/ ]+");
    [
      (if .schema == 1 then empty else "schema must be 1" end),
      (if (.sources|type)=="array" and (.sources|length)>0 then empty else "sources must be non-empty" end),
      (if (.relays|type)=="array" and (.relays|length)>0 then empty else "relays must be non-empty" end),
      ((.sources // [])[] | if https and endswith("/") then empty else "source must be https and end with /: \(.)" end),
      ((.relays // [])[] | if https and (endswith("/")|not) then empty else "relay must be https and not end with /: \(.)" end),
      (.shareSite | if https and endswith("/") then empty else "shareSite must be https and end with /" end),
      (.android.latest.sha256 | if type=="string" and test("^[0-9a-f]{64}$") then empty else "latest.sha256 must be 64 lowercase hex" end),
      (if (.android.latest.versionCode|type)=="number" and (.android.minVersionCode|type)=="number"
          and .android.latest.versionCode >= .android.minVersionCode then empty
       else "latest.versionCode must be >= minVersionCode" end),
      (.android.latest.mirrors | if type=="array" and length>0 and all(.[]; https) then empty else "mirrors must be non-empty https URLs" end)
    ] | .[]' "$1")" || die "payload $1 is not valid JSON/shape"
  [ -z "$errs" ] || die "invalid payload: $(echo "$errs" | tr '\n' ';')"
}

# sign_config PAYLOAD OUT_ENVELOPE
sign_config() {
  [ -f "$CC_KEY_FILE" ] || die "signing key not found: $CC_KEY_FILE"
  (cd "$CC_ROOT" && cargo run -q -p xtask -- sign-config --key "$CC_KEY_FILE" "$1" --out "$2") \
    || die "sign-config failed"
}

# Print diff of two JSON files and require 'yes' (skipped in dry-run).
confirm_diff() {
  local old="$1" new="$2"
  echo "----- payload diff (old -> new) -----"
  diff -u "$old" "$new" || true
  echo "-------------------------------------"
  if [ "$DRY_RUN" = 1 ]; then echo "(dry-run: confirmation skipped)"; return 0; fi
  local ans
  read -r -p "Type 'yes' to publish: " ans
  [ "$ans" = "yes" ] || die "aborted"
}

# upload_config ENVELOPE
upload_config() {
  local env="$1"
  if [ "$DRY_RUN" = 1 ]; then
    echo "[dry-run] aws s3 cp $env s3://$CC_BUCKET/$CC_CONFIG_KEY (application/json, public, max-age=60)"
    return 0
  fi
  aws s3 cp "$env" "s3://$CC_BUCKET/$CC_CONFIG_KEY" \
    --content-type application/json --cache-control "public, max-age=60" >/dev/null \
    || die "config upload failed"
}

# invalidate_all PATH...   (on all three distributions). A failure only warns
# (config is already live); finish_publish exits non-zero at the end.
CC_INVALIDATE_FAILED=0
invalidate_all() {
  local h id
  for h in "${CC_HOSTS[@]}"; do
    id="${h%% *}"
    if [ "$DRY_RUN" = 1 ]; then
      echo "[dry-run] aws cloudfront create-invalidation --distribution-id $id --paths $*"
    elif ! aws cloudfront create-invalidation --distribution-id "$id" --paths "$@" >/dev/null; then
      CC_INVALIDATE_FAILED=1
      echo "WARN: invalidation failed for $id; rerun: aws cloudfront create-invalidation --distribution-id $id --paths $*" >&2
    fi
  done
}

# verify_online ENVELOPE SEQ : every host serves exactly ENVELOPE bytes with the given seq (~2 min per host).
verify_online() {
  local env="$1" seq="$2" h dom got ok tmp attempt
  if [ "$DRY_RUN" = 1 ]; then echo "[dry-run] would verify all hosts serve seq=$seq"; return 0; fi
  tmp="${TMP:-${TMPDIR:-/tmp}}/verify.$$"
  for h in "${CC_HOSTS[@]}"; do
    dom="${h#* }"; ok=0
    # shellcheck disable=SC2034
    for attempt in $(seq 1 20); do
      if curl -fsS --max-time 20 "https://$dom/$CC_CONFIG_KEY" -o "$tmp" 2>/dev/null \
         && cmp -s "$tmp" "$env"; then
        got="$(jq -r .p "$tmp" | b64url_decode | jq -r .seq)"
        [ "$got" = "$seq" ] && { ok=1; break; }
      fi
      sleep 6
    done
    [ "$ok" = 1 ] || { rm -f "$tmp"; die "verify failed on $dom (bytes/seq mismatch after ~2 min); config is uploaded, release/config already updated"; }
    log "verified $dom seq=$seq"
  done
  rm -f "$tmp"
}

# install_local ENVELOPE PAYLOAD : persist into release/config + the iOS factory copy (real runs only)
install_local() {
  if [ "$DRY_RUN" = 1 ]; then return 0; fi
  cp "$2" "$CC_PAYLOAD"; cp "$1" "$CC_ENVELOPE"; cp "$1" "$CC_IOS_ENVELOPE"
}

print_commit_reminder() {
  [ "$DRY_RUN" = 1 ] && return 0
  if [ "$CC_INVALIDATE_FAILED" = 1 ]; then
    echo "Config is live and release/config updated, but invalidation FAILED (see WARN lines above). Rerun them, then commit." >&2
    echo "  git add release/config $CC_IOS_ENVELOPE_REL && git commit -m 'chore(release): config seq bump'" >&2
    exit 1
  fi
  echo
  echo "Done. Remember to record the new config (release/config + the iOS factory copy):"
  echo "  git add release/config $CC_IOS_ENVELOPE_REL && git commit -m 'chore(release): config seq bump'"
}
