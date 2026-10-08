#!/usr/bin/env bash
# 富媒体真机 UAT:Release 构建 App + XCUITest runner(签名走 App Store Connect API key)。
set -euo pipefail
cd "$(dirname "$0")/.."
xcodegen generate >/dev/null
xcodebuild build-for-testing -project ChencangiOS.xcodeproj -scheme ChencangCompanion -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build-uat -allowProvisioningUpdates \
  -authenticationKeyPath "$APP_STORE_CONNECT_P8_PATH" -authenticationKeyID "$APP_STORE_CONNECT_KEY_ID" \
  -authenticationKeyIssuerID "$APP_STORE_CONNECT_ISSUER_ID" 2>&1 | grep -E "error:|BUILD" 
# 通过的用例也保留系统录屏(证据:按住说话浮层/倒计时/上滑取消都只能从录屏里看)
for X in build-uat/Build/Products/*.xctestrun; do
  /usr/libexec/PlistBuddy -c "Set :ChencangCompanionUITests:SystemAttachmentLifetime keepAlways" "$X"
done
