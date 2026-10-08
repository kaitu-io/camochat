# 陈仓媒体中转（rich-media relay）

## 这是什么

语音 / 图片 /视频三种富媒体的对象存储中转。文字信道仍然零服务器、零网络（见根目录 `CLAUDE.md`）；只有这条富媒体腿会发出 HTTP 请求。

流程：客户端把媒体文件用 `blob_secret`（32 字节，只存在于 DR 加密的 `MEDIA_REF` 帧里、随会话密文走）本地加密成 `.cca` blob → 用 `blob_id = base64url(前 16 字节派生值)` 向 `GET /api/upload` 换一条 5 分钟的一次性 S3 直传许可 → `PUT` 到许可指向的 URL → 会话里贴一条 `MEDIA_REF` 引用（含 `blob_id` + `blob_secret` + `kind` + `dur_ms`）→ 对端解出引用后拉 `GET /b/<blob_id>`，本地用 `blob_secret` 解密。

签名参数校验（`lambda/sign.mjs` 的 `validate`）：`blob_id` 必须是 22 字符 base64url；`kind` 只能是 `1`/`2`/`3`；`byte_len` 下限 50 字节（最小合法 `.cca` = 34 字节头 + 16 字节 AEAD tag，与 core `payload::MEDIA_MIN_BLOB_LEN` 一致），上限按 kind：语音 / 图片 2 MiB（2097152）、视频 30 MiB（31457280）。格式不对或低于下限返回 400，超过上限返回 413。

**服务器（签名 Lambda + S3 桶）能看到什么**：一次 PUT 请求的字节数、上传时间、`kind`（语音/图片/视频枚举，不代表内容）；密文本身。
**看不到什么**：明文、`blob_secret`（从不出网）、发件人/收件人身份、会话内容、IP（Lambda 不挂 `AWSLambdaBasicExecutionRole`，物理上写不了任何日志；CloudFront 访问日志保持关闭；WAF 规则 `SampledRequestsEnabled: false`，不留请求样本，WebACL `chencang-media-upload` 也不配置日志）。

> **日志必须保持关闭。** 隐私页（`site/privacy.html` 第 5 节）明文承诺「中转不保存访问日志——CloudFront 访问日志、WAF 日志均未开启，签名函数没有写日志的权限；IP 不落盘」。分发 `E2TRN129L7BYBK` 的标准日志 / v2 日志投递 / 实时日志、WebACL 的 logging configuration、签名 Lambda 的日志权限（`AWSLambdaBasicExecutionRole`）任何一项被打开，隐私页即失实——改动前必须先改隐私页并走产品决策。（2026-09-29 已只读核对线上三项均为关闭。）
**保留多久**：App 内 24 小时后向用户显示「已过期」；云端 S3 生命周期规则（`b/` 前缀，`ExpirationInDays: 1`）按 AWS 每日批处理执行，实际删除约在 1–2 天内完成，不是精确的 24 小时。过期未取的媒体最终会自动消失，符合「陈仓不是网盘」的定位。

## 文件清单

| 文件 | 作用 |
|---|---|
| `template.yaml` | 主栈（ap-northeast-1）：`chencang-media` 桶（1 天生命周期）+ 桶策略（只允许本分发的 CloudFront 服务主体读 `b/*`）+ 签名 Lambda（`nodejs22.x`，无日志权限）+ Lambda Function URL（`AWS_IAM` 鉴权，只信任 CloudFront OAC）+ 两个 CloudFront Origin Access Control |
| `waf.yaml` | WAF 栈（us-east-1，CloudFront WebACL 只能建在这个区域）：仅对 `/api/upload*` 按源 IP 限速（300 秒窗口内 60 次，超限 429） |
| `cf-router.js` | CloudFront Function（viewer-request）：`/m`、`/m/*`（含无 id 的 `/m/`，文字分享链接用它）改写到 `/m/index.html`，`/source`、`/source/` 改写到 `/source/index.html`（落地页/源码页用同一个 index 承接任意子路径） |
| `deploy.sh` | 打包 Lambda 代码 → 传两个 CloudFormation 栈 → 幂等地给 CloudFront 分发（三台同配置：`E2TRN129L7BYBK`、`E3MDHL18OSE5DL`、`E3P8OZYVOPB9EJ`）追加 2 个源站 + 4 条缓存行为 + WAF 关联，**从不改动默认行为、证书、别名、已有源站** |
| `create-mirror-distribution.sh` | 克隆主分发为镜像分发（去别名、改默认证书），用法 `./create-mirror-distribution.sh <N>`；新 ID 需加进 `deploy.sh` 的 `DIST_IDS` |
| `dist-patch.jq` | `deploy.sh` 用的 jq 补丁程序：按 `Origins[].Id` / `CacheBehaviors[].PathPattern` 去重后追加，重复跑结果不变 |
| `smoke.sh` | 线上冒烟：申请签名 → 上传 → 验证重复上传被拒（`If-None-Match: *` → 412）→ 验证长度不匹配被拒 → 下载 → 本地解密核对明文一致 → 验证不存在的 blob 返回 403/404 |
| `lambda/index.mjs` / `lambda/sign.mjs` | 签名函数源码 |
| `../../core/tests/media_smoke_helper.rs` | `smoke.sh` 调用的 core 辅助测试：生成真实 `.cca` blob（`make`）、下载后本地解密校验（`check`） |

`build/`、`lambda/node_modules/`、`lambda/package*.json` 已 gitignore（构建产物 / 本地依赖，不提交）。

## 用法

### 部署

```sh
cd infra/media
./deploy.sh
```

前置条件：`aws` CLI 已授权目标账号，并设置环境变量 `CC_AWS_ACCOUNT`（你的 AWS 账号 ID，脚本未设置即退出），本机有 `jq`、`zip`、`shasum`。脚本会：

1. 把 `lambda/index.mjs` + `lambda/sign.mjs` 打成可复现的 `build/lambda.zip`（固定 mtime、固定文件顺序），算出 `build/SOURCE_SHA256`，上传到 `s3://chencang-site/_deploy/media-signer-<sha>.zip`；
2. `cloudformation deploy` 主栈 `chencang-media`（ap-northeast-1）和 WAF 栈 `chencang-media-waf`（us-east-1）；
3. 发布/更新 CloudFront Function `chencang-site-router` 到 `LIVE`；
4. 用 `dist-patch.jq` 幂等地把两个源站 + 四条缓存行为 + WAF 关联追加进三个分发（`E2TRN129L7BYBK`、`E3MDHL18OSE5DL`、`E3P8OZYVOPB9EJ`），改写 `chencang-site` 桶策略放行三台，等全部 `Deployed` 后规范化比对三台配置，有差异即失败。

重复跑 `deploy.sh` 是安全的：`CacheBehaviors.Quantity` 恒为 4，默认行为的 `TargetOriginId`/证书/别名不变。

### 冒烟

```sh
./smoke.sh [host]   # 默认 d3nnqcgewrb4f0.cloudfront.net
./smoke.sh all      # 依次跑三台
```

跑通 core 里的 `media_smoke_helper` 生成一个真实 `.cca` blob，走线上 `https://<host>/api/upload` → `PUT` → `https://<host>/b/<id>` 整条链路，最后本地解密核对明文，打印 `SMOKE OK (<id>)`。任何一步失败会打印 `FAIL ...` 并以非零退出——检查签名函数参数或分发配置，重新 `deploy.sh` 后再跑。

## 如何自行核对线上代码

`x-cc-source-sha256` 响应头（`/api/upload` 和签名函数的每个响应都带）是**运营者自己声明**的部署 zip SHA-256——它由部署时写入的环境变量原样吐出来，任何人都可以核对它是否与 `/source` 页公开的那份源码打包后算出的哈希一致，但这只是一条「可核对的声明」，不是不可伪造的证明：真正能证明 Lambda 运行的就是这份代码的 `CodeSha256`（CloudFormation/Lambda 内部记录的哈希）只有账号所有者能在控制台/API 里看到，访问者拿不到，也就无法独立验证响应头没有被同一个运营者改写。核对步骤：

```sh
# 1. 从 https://d3nnqcgewrb4f0.cloudfront.net/source 下载 index.mjs 和 sign.mjs
# 2. 按 deploy.sh 第 1 步同样的打包命令：
mkdir -p /tmp/verify && cp index.mjs sign.mjs /tmp/verify/
TZ=UTC touch -t 198001010000 /tmp/verify/*
(cd /tmp/verify && TZ=UTC zip -X -D -q /tmp/verify.zip index.mjs sign.mjs)
shasum -a 256 /tmp/verify.zip
# 3. 对比线上头
curl -sI https://d3nnqcgewrb4f0.cloudfront.net/api/upload | grep -i x-cc-source-sha256
```

两者一致，说明运营者的声明与 `/source` 公开的源码在打包结果上是一致的——即「所声明的代码」与「公开的代码」相符，但不代表已经密码学证明线上真正执行的就是这份代码（见上）。`touch` 前的 `TZ=UTC` 不能省：`touch -t` 把给定的时间戳当作本地时区解释，脚本在 UTC 以外的时区跑会算出不同的 mtime、从而算出不同的 SHA-256，与 CI/线上不一致。

## 以后换国内桶

产品决策是先用现有 AWS S3 起步，后续要换成国内对象存储网关时，只需要动两处：

1. **客户端两个 base URL** —— 上传 `/api/upload`、下载 `/b/<blob_id>`（客户端从签名配置单的 relays 取主机） 目前都由这条 CloudFront 分发承接；换网关时把这两个 `PathPattern` 的 `TargetOriginId` 指向新源站即可，客户端代码里的 URL 常量不用改（主机不变，只是背后的源站换了）。
2. **本目录**（`infra/media/`）—— `template.yaml` 的 `MediaBucket`/`Signer` 资源、`dist-patch.jq` 里 `s3-chencang-media` 源站的 `DomainName`/`OriginAccessControlId`。`waf.yaml`、`cf-router.js`、`smoke.sh` 不受影响。
