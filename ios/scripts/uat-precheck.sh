#!/usr/bin/env bash
# 开跑前只看不动:机主是否在用这台手机。用法: scripts/uat-precheck.sh <ip15|ip12>
# 1) devicectl 查锁屏:锁着就直接报 LOCKED(XCUITest 在锁屏上会卡在「Unlock iPhone to Continue」不退出)。
# 2) 跑 UATShareFirstTests/testSFPrecheck:不启动陈仓、不点任何东西,逐个查装着的 App 谁在前台
#    (bundle 列表现取自 `devicectl device info apps --include-all-apps`),外加 setUp 的通话闸门。
# 3) 证据全部删掉(precheck 截图会拍到机主屏幕)。
# 输出最后一行: FREE(桌面或陈仓在前台) / BUSY <bundle>(机主在用) / LOCKED / CALL / ERROR。
# 2026-10-01 iPhone 15 轮:机主在用的那一个半小时里,这个脚本每 2–3 分钟跑一次,没有打扰到前台。
set -uo pipefail
cd "$(dirname "$0")/.."
DEVNAME="$1"
# shellcheck source=/dev/null
source scripts/uat-devices.env
VAR="UAT_DEVICE_${DEVNAME}"; DEV="${!VAR:-}"
[[ -z "$DEV" ]] && { echo "unknown device $DEVNAME"; exit 2; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
if xcrun devicectl device info lockState --device "$DEV" 2>&1 | grep -q "passcodeRequired: true"; then
  echo LOCKED; exit 0
fi
xcrun devicectl device info apps --device "$DEV" --include-all-apps -j "$TMP/apps.json" -q >/dev/null 2>&1
BUNDLES=$(python3 -c "import json,sys; print(','.join(a['bundleIdentifier'] for a in json.load(open(sys.argv[1]))['result']['apps']))" "$TMP/apps.json" 2>/dev/null)
[[ -z "$BUNDLES" ]] && { echo ERROR; exit 1; }
OUT=$(timeout 240 ./scripts/uat-run.sh "$DEVNAME" UATShareFirstTests/testSFPrecheck "UAT_BUNDLES=$BUNDLES" 2>&1)
pkill -f "testSFPrecheck" 2>/dev/null
NAME=$(echo "$OUT" | sed -n 's/^RESULT \([^ ]*\) rc=.*/\1/p')
# 无线调试下 xcodebuild 跑完用例后常迟迟不退出,被 timeout 杀掉时 $OUT 是空的(管道缓冲丢了):
# 这时改从本次的日志文件里读记录(用例其实已经跑完)。
if [[ -z "$NAME" ]]; then
  LOG=$(ls -t build-uat/results/UATShareFirstTests-testSFPrecheck-"$DEVNAME"-*.log 2>/dev/null | head -1)
  if [[ -n "$LOG" && -n "$(find "$LOG" -newer "$TMP/apps.json" 2>/dev/null)" ]]; then
    OUT=$(grep -a "^UATRECORD" "$LOG"); NAME=$(basename "$LOG" .log)
  fi
fi
[[ -n "$NAME" ]] && rm -rf "build-uat/uat-shots/$NAME" build-uat/results/"$NAME".*
rec() { echo "$OUT" | sed -n "s/^UATRECORD $1=//p" | head -1 | base64 -d 2>/dev/null; }
FG=$(rec sf-precheck-foreground); CALL=$(rec precheck-call)
if [[ -z "$FG" ]]; then echo ERROR; exit 1; fi
if [[ "$CALL" != *"statusBarHits=[]"* || "$CALL" != *"incallFg=[]"* ]]; then echo CALL; exit 0; fi
case "$FG" in
  "<none>"|app.chencang.companion) echo FREE ;;
  *) echo "BUSY $FG" ;;
esac
