import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { rewriteHtml, injectPagesOnly } from './build-pages.mjs';

const H1 = 'https://d3nnqcgewrb4f0.cloudfront.net';

test('existing root file -> relative from root page', () => {
  assert.equal(rewriteHtml('<link href="/favicon.svg">', 'index.html', p => p === 'favicon.svg'), '<link href="favicon.svg">');
});
test('existing file -> relative from nested page', () => {
  assert.equal(rewriteHtml('<a href="/privacy.html">', 'm/index.html', p => p === 'privacy.html'), '<a href="../privacy.html">');
});
test('missing -> H1 absolute', () => {
  assert.equal(rewriteHtml('<a href="/dl/chencang-latest.apk">', 'index.html', () => false), `<a href="${H1}/dl/chencang-latest.apk">`);
  assert.equal(rewriteHtml('<a href="/source">', 'index.html', () => false), `<a href="${H1}/source">`);
});
test('external and relative links untouched', () => {
  assert.equal(rewriteHtml('<a href="https://x/y">', 'index.html', () => true), '<a href="https://x/y">');
  assert.equal(rewriteHtml('<a href="//x/y">', 'index.html', () => false), '<a href="//x/y">');
  assert.equal(rewriteHtml('<a href="#a">', 'index.html', () => true), '<a href="#a">');
});
test('root link -> relative index', () => {
  assert.equal(rewriteHtml('<a href="/">', 'index.html', () => true), '<a href="./">');
  assert.equal(rewriteHtml('<a href="/">', 'm/index.html', () => true), '<a href="../">');
});
test('directory with index.html counts as existing, keeps hash/slash', () => {
  const ex = p => p === 'm/index.html';
  assert.equal(rewriteHtml('<a href="/m/#x">', 'index.html', ex), '<a href="m/#x">');
});
test('src attribute and single quotes', () => {
  assert.equal(rewriteHtml("<img src='/og.svg'>", 'p/index.html', p => p === 'og.svg'), "<img src='../og.svg'>");
});

test('meta content root-absolute -> absolute H1 even if file exists', () => {
  assert.equal(rewriteHtml('<meta property="og:image" content="/og.svg">', 'index.html', () => true), `<meta property="og:image" content="${H1}/og.svg">`);
  assert.equal(rewriteHtml('<meta name="twitter:image" content="/og.svg">', 'm/index.html', () => true), `<meta name="twitter:image" content="${H1}/og.svg">`);
  assert.equal(rewriteHtml('<meta name="description" content="hello">', 'index.html', () => true), '<meta name="description" content="hello">');
});
test('canonical link -> absolute H1', () => {
  assert.equal(rewriteHtml('<link rel="canonical" href="/">', 'index.html', () => true), `<link rel="canonical" href="${H1}/">`);
});

test('pages-only markers: English and Chinese snippets', () => {
  const en = injectPagesOnly('<html lang="en"><!-- pages:kaitu-footer --></html>');
  assert.match(en, /href="https:\/\/kaitu\.io\/">Kaitu</);
  const zh = injectPagesOnly('<html lang="zh-Hans"><!-- pages:kaitu-about --></html>');
  assert.match(zh, /开途 Kaitu/);
  assert.equal(injectPagesOnly('<p>no marker</p>'), '<p>no marker</p>');
});
test('unknown pages marker fails the build', () => {
  assert.throws(() => injectPagesOnly('<!-- pages:nope -->'), /unknown pages marker/);
});
test('site/ sources never contain kaitu.io (CloudFront copies are uploaded as-is)', () => {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
  for (const f of walk(here)) {
    if (/build-pages(\.test)?\.mjs$/.test(f) || !/\.(html|txt|xml|css|svg)$/.test(f)) continue;
    assert.ok(!fs.readFileSync(f, 'utf8').includes('kaitu.io'), `${path.relative(here, f)} mentions kaitu.io`);
  }
});
