#!/usr/bin/env bash
# 富媒体真机 UAT:在一台设备上跑一条 XCUITest。
# 用法: scripts/uat-run.sh <ip15|ip12> <Class/test> [KEY=VALUE ...]   (VALUE 自动 base64,作 TEST_RUNNER_<KEY>,KEY 形如 UAT_INVITE)
# 先 build-for-testing。
# 设备标识(UDID)是机主手机的硬件标识,不进仓库:写在未跟踪的 scripts/uat-devices.env 里
# (格式见 scripts/uat-devices.env.example,已在 ios/.gitignore 里忽略)。
set -euo pipefail
cd "$(dirname "$0")/.."
DEVNAME="$1"
DEVICES_ENV="scripts/uat-devices.env"
if [[ ! -f "$DEVICES_ENV" ]]; then
  echo "missing $DEVICES_ENV (copy scripts/uat-devices.env.example and fill in the UDIDs)"; exit 2
fi
# shellcheck source=/dev/null
source "$DEVICES_ENV"
VAR="UAT_DEVICE_${DEVNAME}"
DEV="${!VAR:-}"
if [[ -z "$DEV" ]]; then
  echo "unknown device $DEVNAME (no $VAR in $DEVICES_ENV)"; exit 2
fi
TEST="$2"; shift 2
ENVS=()
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  ENVS+=("TEST_RUNNER_${k}=$(printf '%s' "$v" | base64 | tr -d '\n')")
done
NAME="$(echo "$TEST" | tr '/' '-')-$DEVNAME-$(date +%H%M%S)"
mkdir -p build-uat/results build-uat/uat-shots
XCTESTRUN=$(ls build-uat/Build/Products/*.xctestrun | head -1)
set +e
env "${ENVS[@]}" xcodebuild test-without-building -xctestrun "$XCTESTRUN" -destination "id=$DEV" \
  -resultBundlePath "build-uat/results/$NAME.xcresult" -collect-test-diagnostics never \
  -only-testing:"ChencangCompanionUITests/$TEST" 2>&1 | tee "build-uat/results/$NAME.log" | grep -E "Test Case|error|UATRECORD|failed|passed|TEST " 
RC=${PIPESTATUS[0]}
set -e
xcrun xcresulttool export attachments --path "build-uat/results/$NAME.xcresult" --output-path "build-uat/uat-shots/$NAME" >/dev/null 2>&1 && python3 scripts/uat-attachments.py "build-uat/uat-shots/$NAME" || true
echo "RESULT $NAME rc=$RC"
exit $RC
