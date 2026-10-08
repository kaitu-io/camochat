# CamoChat（陈仓）静态官网

纯静态页面，不是运行时后端：**文字消息零网络**、不经过任何服务器；唯一的网络用途是语音/图片/视频的密文经媒体中转
（`infra/media/`，源码公开）上传与下载，以及读取已签名的公开配置文件。

内容：`index.html`（英文首页，浏览器语言为中文时自动跳到 `zh.html`）、`zh.html`（中文首页）、`privacy.html`（隐私政策）、
`verify.html` / `verify-zh.html`（验证正版：APK 签名指纹与配置验签公钥）、`style.css`（共用样式，颜色取自 Moyu token）、
`favicon.svg`、`wordmark.svg`（取自 `brand/`）、`og.svg` → `og.png`（分享卡片图，改 og.svg 后用 Chrome headless 截 1200×630 重新生成）、
`robots.txt`、`sitemap.xml`、`llms.txt`；`p/`（配对链接落地页）、`m/`（媒体分享落地页）、
`.well-known/`（iOS Universal Links / Android App Links 校验文件）。

## 发布

同一份文件发布到两处：

1. **GitHub Pages**：`.github/workflows/pages.yml` 用 `node site/build-pages.mjs <outDir>` 构建（把根绝对路径改写为相对路径
   或 CloudFront 绝对地址），推到 `main` 自动发布。页面里的 `<!-- pages:NAME -->` 标记只在 Pages 构建时替换成
   指向 kaitu.io 的链接（内容在 `build-pages.mjs` 的 `PAGES_ONLY`）；`site/` 源文件不得出现 kaitu.io，测试会检查。
   canonical / hreflang / og 地址直接写 Pages 绝对地址，CloudFront 副本的搜索权重归到 Pages。测试：`node --test site/build-pages.test.mjs`。
2. **CloudFront（三个分发同源，S3 桶 `chencang-site`）**：
   - `p/index.html`、`m/index.html`、`.well-known/*`、`/source` 由 `infra/media/deploy.sh` 上传；
   - 根目录页面**手工上传**，改动任一文件后执行（需要 AWS 凭据）：

     ```sh
     for f in index.html zh.html privacy.html verify.html verify-zh.html; do
       aws s3 cp "site/$f" "s3://chencang-site/$f" --content-type "text/html; charset=utf-8" --cache-control "max-age=300" --region ap-northeast-1
     done
     for f in favicon.svg og.svg wordmark.svg; do
       aws s3 cp "site/$f" "s3://chencang-site/$f" --content-type "image/svg+xml" --cache-control "max-age=300" --region ap-northeast-1
     done
     aws s3 cp site/style.css s3://chencang-site/style.css --content-type "text/css; charset=utf-8" --cache-control "max-age=300" --region ap-northeast-1
     aws s3 cp site/og.png s3://chencang-site/og.png --content-type "image/png" --cache-control "max-age=300" --region ap-northeast-1
     for f in robots.txt llms.txt; do
       aws s3 cp "site/$f" "s3://chencang-site/$f" --content-type "text/plain; charset=utf-8" --cache-control "max-age=300" --region ap-northeast-1
     done
     ```

     `p/`、`m/` 落地页改动后由 `infra/media/deploy.sh` 重新上传。`sitemap.xml` 只列 Pages 地址，不必传 CloudFront。
