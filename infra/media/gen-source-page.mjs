// 生成 https://d3nnqcgewrb4f0.cloudfront.net/source ：逐文件展示媒体中转的全部源码。
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const FILES = ['lambda/index.mjs', 'lambda/sign.mjs', 'template.yaml', 'waf.yaml', 'cf-router.js', 'dist-patch.jq', 'deploy.sh', 'create-mirror-distribution.sh', 'gen-source-page.mjs'];
const sha = readFileSync(join(here, 'build/SOURCE_SHA256'), 'utf8').trim();
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const out = join(here, 'build/source');
mkdirSync(join(out, 'files/lambda'), { recursive: true });
const sections = FILES.map((f) => {
  copyFileSync(join(here, f), join(out, 'files', f));
  const body = readFileSync(join(here, f), 'utf8');
  return `<section><h2 id="${f}">${f} <a href="/source/files/${f}" download>下载</a></h2><pre><code>${esc(body)}</code></pre></section>`;
}).join('\n');
const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>陈仓中转源码</title><style>
:root{--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--code:#f5f5f7}
@media (prefers-color-scheme:dark){:root{--bg:#111;--fg:#f5f5f7;--muted:#a1a1a6;--code:#1c1c1e}}
body{margin:0;padding:16px;max-width:880px;margin-inline:auto;background:var(--bg);color:var(--fg);font:15px/1.6 -apple-system,system-ui,sans-serif}
pre{background:var(--code);padding:12px;overflow-x:auto;border-radius:8px;font-size:12px}h2{font-size:15px;margin-top:32px}p{color:var(--muted)}
</style></head><body>
<h1>陈仓媒体中转 · 全部源码</h1>
<p>陈仓的文字消息不经过任何服务器。语音、图片、视频先在手机上加密，再以密文形式经这里中转；App 内 24 小时后显示「已过期」，云端按 S3 生命周期约 1–2 天内自动删除。服务器拿不到任何密钥，也不记录 IP。下面就是线上运行的全部代码。</p>
<p>运营者声明的线上部署包 SHA-256：<code>${sha}</code>。可核对方法：下载 lambda/index.mjs 与 lambda/sign.mjs，按 deploy.sh 第 1 步打包后计算 SHA-256，与此值以及 <code>curl -sI "https://d3nnqcgewrb4f0.cloudfront.net/api/upload" | grep x-cc-source-sha256</code> 的结果比对——一致说明这份公开源码与运营者的声明相符，但这是一条可核对的声明，不是密码学证明（Lambda 真正执行代码的哈希只有账号所有者能看到）。</p>
${sections}
</body></html>`;
writeFileSync(join(out, 'index.html'), html);
console.log(`wrote ${join(out, 'index.html')}`);
