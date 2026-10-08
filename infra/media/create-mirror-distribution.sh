#!/usr/bin/env bash
# 克隆主分发 E2TRN129L7BYBK 为一个镜像分发：源站、行为、策略、函数、WAF 全同，
# 只去掉自定义域名、改用 *.cloudfront.net 默认证书。用法：./create-mirror-distribution.sh <N>
# 建好后把新 ID 加进 deploy.sh 的 DIST_IDS，再跑 ./deploy.sh 让三台配置对齐。
set -euo pipefail
N=${1:?usage: create-mirror-distribution.sh <N>}
SRC=E2TRN129L7BYBK
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
aws cloudfront get-distribution-config --id "$SRC" --query DistributionConfig --output json \
  | jq --arg ref "chencang-mirror-${N}-$(date +%s)" --arg c "chencang mirror ${N}" '
      .CallerReference = $ref
      | .Comment = $c
      | .Aliases = {Quantity: 0}
      | .ViewerCertificate = {CloudFrontDefaultCertificate: true, MinimumProtocolVersion: "TLSv1", CertificateSource: "cloudfront"}' \
  > "$TMP"
aws cloudfront create-distribution --distribution-config "file://$TMP" \
  --query 'Distribution.{Id:Id,Domain:DomainName,Status:Status}' --output table
