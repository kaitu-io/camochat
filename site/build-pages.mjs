// Build the GitHub Pages output from site/.
// Usage: node site/build-pages.mjs <outDir>
// Root-absolute links (href="/X" / src="/X") are rewritten only in the output:
// X exists in site/ -> relative path; otherwise -> CloudFront host H1.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const H1 = 'https://d3nnqcgewrb4f0.cloudfront.net';
const EXCLUDE = new Set(['build-pages.mjs', 'build-pages.test.mjs', 'README.md']);

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
    if (f.endsWith('.html')) fs.writeFileSync(dest, rewriteHtml(fs.readFileSync(path.join(here, f), 'utf8'), f, exists));
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
