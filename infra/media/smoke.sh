#!/usr/bin/env bash
# 线上冒烟：申请 → 上传 → 重复上传被拒 → 错误长度/错误 Content-Type 被拒 → 下载 → 响应头核对 → 本地解密。
# 用法：./smoke.sh [host]（默认 H1）；./smoke.sh all 依次跑三台。
set -euo pipefail
H1=d3nnqcgewrb4f0.cloudfront.net
H2=dzr6ldib1w492.cloudfront.net
H3=dee2wx7h03hqb.cloudfront.net
if [ "${1:-}" = all ]; then
  for h in $H1 $H2 $H3; do echo "--- $h"; "$0" "$h"; done
  exit 0
fi
HOST=${1:-$H1}
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
D=$(mktemp -d); export SMOKE_DIR=$D
trap 'rm -rf "$D"' EXIT
(cd "$ROOT" && cargo test -q -p chencang-core --test media_smoke_helper -- --ignored make >/dev/null)
ID=$(cat "$D/id"); LEN=$(wc -c < "$D/blob" | tr -d ' ')
sign() { curl -sS -D "$D/h" "https://$HOST/api/upload?blob_id=$1&byte_len=$2&kind=2"; }
URL=$(sign "$ID" "$LEN" | jq -r .url)
grep -qi "^x-cc-source-sha256: $(cat "$ROOT/infra/media/build/SOURCE_SHA256")" "$D/h" || { echo "FAIL sha header"; exit 1; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
# 每次运行都用新的随机 blob_id（16 字节 → 22 字符 base64url，与真实 id 同形），
# 避免固定 id 在第二次运行时因「已存在」返回 412，把本该暴露的回归掩盖掉。
rid() { head -c 16 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
[ "$(code -X PUT -H 'Content-Type: application/octet-stream' -H 'If-None-Match: *' --data-binary @"$D/blob" "$URL")" = 200 ] || { echo "FAIL upload"; exit 1; }
URL2=$(sign "$ID" "$LEN" | jq -r .url)
[ "$(code -X PUT -H 'Content-Type: application/octet-stream' -H 'If-None-Match: *' --data-binary @"$D/blob" "$URL2")" = 412 ] || { echo "FAIL overwrite not rejected"; exit 1; }
BAD=$(curl -sS "https://$HOST/api/upload?blob_id=$(rid)&byte_len=$((LEN+1))&kind=2" | jq -r .url)
[ "$(code -X PUT -H 'Content-Type: application/octet-stream' -H 'If-None-Match: *' --data-binary @"$D/blob" "$BAD")" != 200 ] || { echo "FAIL length not bound"; exit 1; }
CTID=$(rid)
CTURL=$(sign "$CTID" "$LEN" | jq -r .url)
[ "$(code -X PUT -H 'Content-Type: text/html' -H 'If-None-Match: *' --data-binary @"$D/blob" "$CTURL")" != 200 ] || { echo "FAIL content-type not bound"; exit 1; }
[ "$(code "https://$HOST/api/upload?blob_id=../x&byte_len=1&kind=2")" = 400 ] || { echo "FAIL validation"; exit 1; }
[ "$(code "https://$HOST/api/upload?blob_id=$ID&byte_len=99999999&kind=1")" = 413 ] || { echo "FAIL limit"; exit 1; }
curl -sS -D "$D/dlh" -o "$D/downloaded" "https://$HOST/b/$ID"
grep -qi '^content-type: application/octet-stream' "$D/dlh" || { echo "FAIL download content-type"; exit 1; }
grep -qi '^x-content-type-options: nosniff' "$D/dlh" || { echo "FAIL download nosniff header"; exit 1; }
(cd "$ROOT" && cargo test -q -p chencang-core --test media_smoke_helper -- --ignored check >/dev/null)
MISSING=$(rid)
[ "$(code "https://$HOST/b/$MISSING")" = 403 ] || [ "$(code "https://$HOST/b/$MISSING")" = 404 ] || { echo "FAIL missing blob"; exit 1; }
LP=$(code "https://$HOST/m/x"); [ "$LP" = 200 ] || { echo "FAIL landing page /m/x ($LP)"; exit 1; }
PG=$(code "https://$HOST/p/"); [ "$PG" = 200 ] || { echo "FAIL pairing page /p/ ($PG)"; exit 1; }
PB=$(code "https://$HOST/p"); [ "$PB" = 200 ] || { echo "FAIL pairing page /p ($PB)"; exit 1; }
SP=$(code "https://$HOST/source"); [ "$SP" = 200 ] || { echo "FAIL /source ($SP)"; exit 1; }
echo "SMOKE OK ($HOST $ID)"
