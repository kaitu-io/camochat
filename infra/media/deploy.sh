#!/usr/bin/env bash
# 陈仓媒体中转部署。全文公开在各分发的 /source 页（如 https://d3nnqcgewrb4f0.cloudfront.net/source）。
set -euo pipefail
cd "$(dirname "$0")"
REGION=ap-northeast-1
# 三个同配置分发：H1（主）、H2、H3（镜像，见 create-mirror-distribution.sh）
DIST_IDS=(E2TRN129L7BYBK E3MDHL18OSE5DL E3P8OZYVOPB9EJ)
ACCOUNT="${CC_AWS_ACCOUNT:?set CC_AWS_ACCOUNT (your AWS account id)}"
DIST_ARNS=""; ARN_JSON="[]"
for id in "${DIST_IDS[@]}"; do
  arn="arn:aws:cloudfront::${ACCOUNT}:distribution/${id}"
  DIST_ARNS="${DIST_ARNS:+$DIST_ARNS,}$arn"
  ARN_JSON=$(jq -c --arg a "$arn" '. + [$a]' <<<"$ARN_JSON")
done
CODE_BUCKET=chencang-site          # 复用已有私有桶存放部署包，键在 _deploy/ 下（CloudFront 默认行为可读，内容本就公开）

# 1) 可复现打包：固定 mtime + 固定顺序 + 去掉额外属性
rm -rf build && mkdir -p build/pkg
cp lambda/index.mjs lambda/sign.mjs build/pkg/
TZ=UTC touch -t 198001010000 build/pkg/*
(cd build/pkg && TZ=UTC zip -X -D -q ../lambda.zip index.mjs sign.mjs)
SHA=$(shasum -a 256 build/lambda.zip | cut -d' ' -f1)
echo "$SHA" > build/SOURCE_SHA256
KEY="_deploy/media-signer-${SHA}.zip"
aws s3 cp build/lambda.zip "s3://${CODE_BUCKET}/${KEY}" --region "$REGION" >/dev/null

# 2) 主栈 + WAF 栈
aws cloudformation deploy --region "$REGION" --stack-name chencang-media --template-file template.yaml \
  --capabilities CAPABILITY_IAM --no-fail-on-empty-changeset \
  --parameter-overrides CodeBucket="$CODE_BUCKET" CodeKey="$KEY" SourceSha256="$SHA" DistributionArns="$DIST_ARNS"
aws cloudformation deploy --region us-east-1 --stack-name chencang-media-waf --template-file waf.yaml --no-fail-on-empty-changeset
out() { aws cloudformation describe-stacks --region "$1" --stack-name "$2" --query "Stacks[0].Outputs[?OutputKey=='$3'].OutputValue" --output text; }
BUCKET_DOM=$(out "$REGION" chencang-media BucketRegionalDomain)
SIGNER_DOM=$(out "$REGION" chencang-media SignerUrlDomain)
MEDIA_OAC=$(out "$REGION" chencang-media MediaOacId)
SIGNER_OAC=$(out "$REGION" chencang-media SignerOacId)
MEDIA_RHP=$(out "$REGION" chencang-media MediaResponseHeadersPolicyId)
MEDIA_CP=$(out "$REGION" chencang-media MediaCachePolicyId)
ACL_ARN=$(out us-east-1 chencang-media-waf AclArn)

# 3) CloudFront Function（发布 LIVE）
FN=chencang-site-router
if aws cloudfront describe-function --name "$FN" >/dev/null 2>&1; then
  ETAG=$(aws cloudfront describe-function --name "$FN" --query ETag --output text)
  ETAG=$(aws cloudfront update-function --name "$FN" --if-match "$ETAG" --function-config Comment=chencang,Runtime=cloudfront-js-2.0 --function-code fileb://cf-router.js --query ETag --output text)
else
  ETAG=$(aws cloudfront create-function --name "$FN" --function-config Comment=chencang,Runtime=cloudfront-js-2.0 --function-code fileb://cf-router.js --query ETag --output text)
fi
aws cloudfront publish-function --name "$FN" --if-match "$ETAG" >/dev/null
FN_ARN=$(aws cloudfront describe-function --name "$FN" --stage LIVE --query FunctionSummary.FunctionMetadata.FunctionARN --output text)

# 4a) chencang-site 桶策略（不在模板里）：幂等地让三个分发都能读，保留其他语句
SITE_POLICY=$(aws s3api get-bucket-policy --bucket "$CODE_BUCKET" --query Policy --output text)
jq --argjson arns "$ARN_JSON" '
  .Statement |= map(if .Sid == "AllowCloudFrontServicePrincipalReadOnly"
                    then .Condition.StringEquals["AWS:SourceArn"] = $arns else . end)' <<<"$SITE_POLICY" > build/site-policy.json
jq -e '[.Statement[] | select(.Sid == "AllowCloudFrontServicePrincipalReadOnly")] | length == 1' build/site-policy.json >/dev/null \
  || { echo "site bucket policy lacks the AllowCloudFrontServicePrincipalReadOnly statement" >&2; exit 1; }
aws s3api put-bucket-policy --bucket "$CODE_BUCKET" --policy file://build/site-policy.json

# 4b) 幂等地给每个分发追加两个源站 + 六条行为 + WAF（默认行为、别名、证书不动）
for DIST_ID in "${DIST_IDS[@]}"; do
  aws cloudfront get-distribution-config --id "$DIST_ID" > "build/dist-${DIST_ID}.json"
  DETAG=$(jq -r .ETag "build/dist-${DIST_ID}.json")
  jq --arg bd "$BUCKET_DOM" --arg sd "$SIGNER_DOM" --arg mo "$MEDIA_OAC" --arg so "$SIGNER_OAC" \
     --arg fn "$FN_ARN" --arg acl "$ACL_ARN" --arg rhp "$MEDIA_RHP" --arg cp "$MEDIA_CP" \
     -f dist-patch.jq "build/dist-${DIST_ID}.json" > "build/dist-new-${DIST_ID}.json"
  aws cloudfront update-distribution --id "$DIST_ID" --if-match "$DETAG" --distribution-config "file://build/dist-new-${DIST_ID}.json" >/dev/null
done
for DIST_ID in "${DIST_IDS[@]}"; do aws cloudfront wait distribution-deployed --id "$DIST_ID"; done

# 4c) 三台一致性核对：规范化后 diff，非空即失败
NORM='.DistributionConfig | {
  WebACLId,
  Origins: [.Origins.Items[] | {Id, DomainName, OAC: .OriginAccessControlId}] | sort_by(.Id),
  CacheBehaviors: [(.CacheBehaviors.Items // [])[] | {PathPattern, TargetOriginId, CachePolicyId, ResponseHeadersPolicyId, FunctionAssociations}] | sort_by(.PathPattern),
  Default: .DefaultCacheBehavior.TargetOriginId }'
for DIST_ID in "${DIST_IDS[@]}"; do
  aws cloudfront get-distribution-config --id "$DIST_ID" | jq -S "$NORM" > "build/norm-${DIST_ID}.json"
  echo "== ${DIST_ID}"; cat "build/norm-${DIST_ID}.json"
done
for DIST_ID in "${DIST_IDS[@]:1}"; do
  diff "build/norm-${DIST_IDS[0]}.json" "build/norm-${DIST_ID}.json" || { echo "DISTRIBUTION CONFIG DRIFT: ${DIST_IDS[0]} vs ${DIST_ID}" >&2; exit 1; }
done
echo "distributions identical"

# 5) 发布源码页与落地页
node gen-source-page.mjs
aws s3 sync build/source "s3://chencang-site/source" --delete --cache-control "no-cache" --region "$REGION"
aws s3 cp build/source/files "s3://chencang-site/source/files" --recursive --content-type "text/plain; charset=utf-8" --cache-control "no-cache" --region "$REGION"
aws s3 cp ../../site/m/index.html "s3://chencang-site/m/index.html" --content-type "text/html; charset=utf-8" --cache-control "max-age=300" --region "$REGION"
aws s3 cp ../../site/p/index.html "s3://chencang-site/p/index.html" --content-type "text/html; charset=utf-8" --cache-control "max-age=300" --region "$REGION"
for f in apple-app-site-association assetlinks.json; do
  aws s3 cp "../../site/.well-known/$f" "s3://chencang-site/.well-known/$f" --content-type "application/json" --cache-control "max-age=300" --region "$REGION"
done

echo "deployed; SOURCE_SHA256=$SHA"
