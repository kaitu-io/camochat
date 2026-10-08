// Build the GitHub Pages output from site/.
// Usage: node site/build-pages.mjs <outDir>
// Root-absolute links (href="/X" / src="/X") are rewritten only in the output:
// X exists in site/ -> relative path; otherwise -> CloudFront host H1.
// `<!-- pages:NAME -->` markers become Pages-only snippets (links to kaitu.io). The
// CloudFront copies are uploaded from site/ untouched, so they never carry them.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const H1 = 'https://d3nnqcgewrb4f0.cloudfront.net';
const EXCLUDE = new Set(['build-pages.mjs', 'build-pages.test.mjs', 'README.md']);

const PAGES_ONLY = {
  'kaitu-about': {
    en: '<p class="under">CamoChat is built and maintained by the team behind <a href="https://kaitu.io/">Kaitu</a>.</p>',
    zh: '<p class="under">陈仓由 <a href="https://kaitu.io/">开途 Kaitu</a> 团队开发和维护。</p>',
  },
  'kaitu-footer': {
    en: '<p class="made-by">An open-source project by <a href="https://kaitu.io/">Kaitu</a>.</p>',
    zh: '<p class="made-by">由 <a href="https://kaitu.io/">开途 Kaitu</a> 出品的开源项目。</p>',
  },
};

export function injectPagesOnly(html) {
  const lang = /<html\b[^>]*\blang=["']zh/i.test(html) ? 'zh' : 'en';
  return html.replace(/<!-- pages:([a-z-]+) -->/g, (_m, name) => {
    if (!PAGES_ONLY[name]) throw new Error(`unknown pages marker: ${name}`);
    return PAGES_ONLY[name][lang];
  });
}

export function rewriteHtml(html, pageRelPath, exists) {
  const pageDir = path.posix.dirname(pageRelPath);
  // meta content (og:image, og:url, ...) and <link rel=canonical>: crawlers need absolute URLs.
  html = html
    .replace(/\bcontent=(["'])\/(?!\/)([^"']*)\1/g, (_m, q, rest) => `content=${q}${H1}/${rest}${q}`)
    .replace(/<link\b[^>]*\brel=(["'])canonical\1[^>]*>/g, tag =>
      tag.replace(/\bhref=(["'])\/(?!\/)([^"']*)\1/, (_m, q, rest) => `href=${q}${H1}/${rest}${q}`));
  return html.replace(/\b(href|src)=(["'])\/(?!\/)([^"']*)\2/g, (_m, attr, q, rest) => {
    const m = /^([^?#]*)(.*)$/.exec(rest);
    const p = m[1];
    const suffix = m[2];
    const bare = p.replace(/\/+$/, '');
    const found = bare === '' || exists(bare) || exists(`${bare}/index.html`);
    if (!found) return `${attr}=${q}${H1}/${rest}${q}`;
    let rel = path.posix.relative(pageDir, bare);
    if (p.endsWith('/') || bare === '') rel = (rel === '' ? '.' : rel) + '/';
    return `${attr}=${q}${rel}${suffix}${q}`;
  });
}

function walk(dir, base = '') {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const rel = base ? `${base}/${e.name}` : e.name;
    if (!base && EXCLUDE.has(e.name)) return [];
    return e.isDirectory() ? walk(path.join(dir, e.name), rel) : [rel];
  });
}

function main(outDir) {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const files = walk(here);
  const set = new Set(files);
  const dirs = new Set(files.flatMap(f => { const o = []; let d = path.posix.dirname(f); while (d !== '.') { o.push(d); d = path.posix.dirname(d); } return o; }));
  const exists = p => set.has(p) || dirs.has(p);
  fs.rmSync(outDir, { recursive: true, force: true });
  for (const f of files) {
    const dest = path.join(outDir, f);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    if (f.endsWith('.html')) fs.writeFileSync(dest, rewriteHtml(injectPagesOnly(fs.readFileSync(path.join(here, f), 'utf8')), f, exists));
    else fs.copyFileSync(path.join(here, f), dest);
  }
  const cfg = path.join(here, '..', 'release', 'config', 'chencang-config.json');
  fs.mkdirSync(path.join(outDir, 'c'), { recursive: true });
  fs.copyFileSync(cfg, path.join(outDir, 'c', 'chencang-config.json'));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  if (!process.argv[2]) { console.error('usage: node build-pages.mjs <outDir>'); process.exit(2); }
  main(process.argv[2]);
}
