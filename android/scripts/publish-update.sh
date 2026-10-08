#!/usr/bin/env bash
# Stage 2 of the release: push a tagged Release APK to the CDN and publish a new
# signed config.
# usage: publish-update.sh <tag> [--force | --min-code N] --notes-zh F --notes-en F [--dry-run]
#        (hidden: --apk PATH  use a local APK instead of the Release asset; skips the .sha256 compare)
set -euo pipefail
# shellcheck source=lib-config.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib-config.sh"

usage() { die "usage: publish-update.sh <tag> [--force | --min-code N] --notes-zh FILE --notes-en FILE [--dry-run]"; }

TAG="" FORCE=0 MIN_CODE="" NOTES_ZH="" NOTES_EN="" LOCAL_APK=""
# shellcheck disable=SC2034
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=1 ;;
    --min-code) [ $# -ge 2 ] || usage; MIN_CODE="$2"; shift ;;
    --notes-zh) [ $# -ge 2 ] || usage; NOTES_ZH="$2"; shift ;;
    --notes-en) [ $# -ge 2 ] || usage; NOTES_EN="$2"; shift ;;
    --apk) [ $# -ge 2 ] || usage; LOCAL_APK="$2"; shift ;;
    --dry-run) DRY_RUN=1 ;;
    -*) usage ;;
    *) if [ -z "$TAG" ]; then TAG="$1"; else usage; fi ;;
  esac
  shift
done
[ -n "$TAG" ] && [ -f "$NOTES_ZH" ] && [ -f "$NOTES_EN" ] || usage
[ -z "$LOCAL_APK" ] || [ "$DRY_RUN" = 1 ] || die "--apk is dry-run only"
[ -s "$NOTES_ZH" ] && [ -s "$NOTES_EN" ] || die "notes files must not be empty"
[ "$FORCE" = 1 ] && [ -n "$MIN_CODE" ] && die "--force and --min-code are mutually exclusive"
[ -z "$MIN_CODE" ] || [[ "$MIN_CODE" =~ ^[0-9]+$ ]] || die "--min-code must be an integer"
[[ "$TAG" == android-v* ]] || die "tag must look like android-v<version>"
VERSION="${TAG#android-v}"

cc_need jq aws curl cargo base64 diff shasum
[ -n "$LOCAL_APK" ] || cc_need gh

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/36.0.0"
[ -d "$BT" ] || BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"
[ -x "$BT/aapt2" ] && [ -x "$BT/apksigner" ] || die "aapt2/apksigner not found under $SDK/build-tools"

CC_REPO="kaitu-io/camochat"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
APK_NAME="chencang-$VERSION.apk"

# 0. base payload must equal the online one
log "checking release/config/payload.json against the online config"
assert_local_matches_online "$TMP"

# 1. obtain + verify the APK
if [ -n "$LOCAL_APK" ]; then
  [ -f "$LOCAL_APK" ] || die "--apk file not found: $LOCAL_APK"
  cp "$LOCAL_APK" "$TMP/$APK_NAME"
  log "using local APK $LOCAL_APK (no .sha256 comparison)"
else
  log "downloading Release $TAG assets"
  gh release download "$TAG" --repo "$CC_REPO" --dir "$TMP" \
      --pattern "$APK_NAME" --pattern "$APK_NAME.sha256" >/dev/null || true
  [ -f "$TMP/$APK_NAME" ] || die "asset $APK_NAME not found on release $TAG (releases before the chencang-<version>.apk naming used a different asset name)"
  [ -f "$TMP/$APK_NAME.sha256" ] || die "asset $APK_NAME.sha256 not found on release $TAG"
fi
SHA="$(shasum -a 256 "$TMP/$APK_NAME" | awk '{print $1}')"
if [ -z "$LOCAL_APK" ]; then
  expect="$(awk '{print $1}' "$TMP/$APK_NAME.sha256")"
  [ "$SHA" = "$expect" ] || die "sha256 mismatch: computed $SHA, release says $expect"
fi
SIZE="$(wc -c < "$TMP/$APK_NAME" | tr -d ' ')"

cert="$("$BT/apksigner" verify --print-certs "$TMP/$APK_NAME" \
  | grep -ioE 'certificate SHA-256 digest: [0-9a-f]+' | grep -ioE '[0-9a-f]{64}' | head -1 || true)"
[ "$cert" = "$CC_EXPECTED_CERT" ] || die "APK signing cert ${cert:-<none>} != expected $CC_EXPECTED_CERT"

badging="$("$BT/aapt2" dump badging "$TMP/$APK_NAME" | sed -n 1p)"
NEW_CODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
NEW_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
[ "$NEW_NAME" = "$VERSION" ] || die "APK versionName '$NEW_NAME' does not match tag version '$VERSION'"

# 2. versionCode must exceed the online latest
ONLINE_CODE="$(jq -r .android.latest.versionCode "$TMP/online-payload.canon.json")"
version_gt "$NEW_CODE" "$ONLINE_CODE" \
  || die "versionCode must be greater than online (new=$NEW_CODE, online=$ONLINE_CODE)"

# 4. build new payload
if [ "$FORCE" = 1 ]; then NEW_MIN="$NEW_CODE"
elif [ -n "$MIN_CODE" ]; then NEW_MIN="$MIN_CODE"
else NEW_MIN="$(jq -r .android.minVersionCode "$CC_PAYLOAD")"; fi
OLD_SEQ="$(jq -r .seq "$CC_PAYLOAD")"
NEW_SEQ=$((OLD_SEQ + 1))

mirrors="$(jq -c --arg v "$VERSION" --arg t "$TAG" --arg repo "$CC_REPO" \
  '[.relays[] | . + "/dl/chencang-" + $v + ".apk"] + ["https://github.com/" + $repo + "/releases/download/" + $t + "/chencang-" + $v + ".apk"]' "$CC_PAYLOAD")"
zh="$(jq -Rs 'sub("^\\s+";"") | sub("\\s+$";"")' < "$NOTES_ZH")"
en="$(jq -Rs 'sub("^\\s+";"") | sub("\\s+$";"")' < "$NOTES_EN")"

jq --argjson seq "$NEW_SEQ" --argjson min "$NEW_MIN" --argjson code "$NEW_CODE" \
   --arg name "$NEW_NAME" --arg sha "$SHA" --argjson size "$SIZE" \
   --argjson mirrors "$mirrors" --argjson zh "$zh" --argjson en "$en" \
   '.seq = $seq | .android.minVersionCode = $min
    | .android.latest = {versionCode:$code, versionName:$name, sha256:$sha, size:$size,
                         mirrors:$mirrors, notes:{zh:$zh, en:$en}}' \
   "$CC_PAYLOAD" > "$TMP/payload.new.json"

validate_payload "$TMP/payload.new.json"
confirm_diff "$TMP/online-payload.canon.json" <(jq -S . "$TMP/payload.new.json")
sign_config "$TMP/payload.new.json" "$TMP/envelope.new.json"

# 3. + 5. upload (APK first, so the config never points at a missing file)
echo
echo "Objects to upload to s3://$CC_BUCKET:"
echo "  dl/$APK_NAME  ($SIZE bytes, application/vnd.android.package-archive, immutable 1y)"
echo "  dl/chencang-latest.apk  (max-age=300)"
echo "  $CC_CONFIG_KEY  (application/json, max-age=60)"
echo "  invalidate /c/* and /dl/chencang-latest.apk on ${#CC_HOSTS[@]} distributions"
echo "  minVersionCode=$NEW_MIN latest=$NEW_CODE seq=$NEW_SEQ"
if [ "$DRY_RUN" = 1 ]; then
  echo "[dry-run] nothing uploaded or written."
else
  # immutable object: if it already exists it must be byte-identical (sha256 metadata)
  if existing="$(aws s3api head-object --bucket "$CC_BUCKET" --key "dl/$APK_NAME" \
        --query 'Metadata.sha256' --output text 2>/dev/null)"; then
    [ "$existing" = "$SHA" ] || die "dl/$APK_NAME already exists with different content (sha256 metadata: $existing); refusing to overwrite an immutable object"
    log "dl/$APK_NAME already uploaded with identical sha256; skipping"
  else
    aws s3 cp "$TMP/$APK_NAME" "s3://$CC_BUCKET/dl/$APK_NAME" \
      --content-type application/vnd.android.package-archive \
      --cache-control "public, max-age=31536000, immutable" \
      --metadata "sha256=$SHA" >/dev/null || die "APK upload failed"
  fi
  aws s3 cp "$TMP/$APK_NAME" "s3://$CC_BUCKET/dl/chencang-latest.apk" \
    --content-type application/vnd.android.package-archive \
    --cache-control "public, max-age=300" >/dev/null || die "latest APK upload failed"
fi
upload_config "$TMP/envelope.new.json"
install_local "$TMP/envelope.new.json" "$TMP/payload.new.json"
invalidate_all '/c/*' '/dl/chencang-latest.apk'
verify_online "$TMP/envelope.new.json" "$NEW_SEQ"
print_commit_reminder
