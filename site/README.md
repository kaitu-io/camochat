# CamoChat（陈仓）静态官网

纯静态页面，不是运行时后端：**文字消息零网络**、不经过任何服务器；唯一的网络用途是语音/图片/视频的密文经媒体中转
（`infra/media/`，源码公开）上传与下载，以及读取已签名的公开配置文件。

内容：`index.html`（首页）、`privacy.html`（隐私政策）、`verify.html`（验证正版：APK 签名指纹与配置验签公钥）、
`favicon.svg`、`og.svg`、`robots.txt`、`llms.txt`；`p/`（配对链接落地页）、`m/`（媒体分享落地页）、
`.well-known/`（iOS Universal Links / Android App Links 校验文件）。

## 发布

同一份文件发布到两处：

1. **GitHub Pages**：`.github/workflows/pages.yml` 用 `node site/build-pages.mjs <outDir>` 构建（把根绝对路径改写为相对路径
   或 CloudFront 绝对地址），推到 `main` 自动发布。测试：`node --test site/build-pages.test.mjs`。
2. **CloudFront（三个分发同源，S3 桶 `chencang-site`）**：
   - `p/index.html`、`m/index.html`、`.well-known/*`、`/source` 由 `infra/media/deploy.sh` 上传；
   - 根目录页面**手工上传**，改动任一文件后执行（需要 AWS 凭据）：

     ```sh
     for f in index.html privacy.html verify.html; do
       aws s3 cp "site/$f" "s3://chencang-site/$f" --content-type "text/html; charset=utf-8" --cache-control "max-age=300" --region ap-northeast-1
     done
     for f in favicon.svg og.svg; do
       aws s3 cp "site/$f" "s3://chencang-site/$f" --content-type "image/svg+xml" --cache-control "max-age=300" --region ap-northeast-1
     done
     for f in robots.txt llms.txt; do
       aws s3 cp "site/$f" "s3://chencang-site/$f" --content-type "text/plain; charset=utf-8" --cache-control "max-age=300" --region ap-northeast-1
     done
     ```

     `verify.html` 是新加的页面，首次发布到 CloudFront 前必须按上面执行一次。
