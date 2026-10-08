import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const load = (f) => JSON.parse(readFileSync(new URL(`../tokens/moyu/${f}`, import.meta.url), 'utf8'));
const primitive = load('primitive.json');
const semantic = load('semantic.json');

const resolve = (v) => {
  const m = /^\{(.+)\}$/.exec(v);
  return m ? resolve(primitive[m[1]].$value) : v;
};
const pair = (name) => {
  const v = semantic[name].$value;
  return typeof v === 'object' ? { light: resolve(v.light), dark: resolve(v.dark) } : { light: resolve(v), dark: resolve(v) };
};

// WCAG 2.x 相对亮度与对比度
const lum = (hex) => {
  const h = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map((i) => {
    const c = parseInt(h.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

for (let i = 1; i <= 8; i++) {
  test(`白字在 avatar-${i} 上对比度 ≥ 3`, () => {
    const bg = pair(`avatar-${i}`);
    const fg = pair('avatar-glyph');
    for (const mode of ['light', 'dark']) {
      assert.ok(contrast(fg[mode], bg[mode]) >= 3, `${mode}: ${contrast(fg[mode], bg[mode])}`);
    }
  });
}

for (const mode of ['light', 'dark']) {
  test(`accent-primary 在 accent-container 上 ≥ 3 (${mode})`, () => {
    const fg = pair('accent-primary')[mode];
    const bg = pair('accent-container')[mode];
    assert.ok(contrast(fg, bg) >= 3, `${contrast(fg, bg)}`);
  });
}

test('avatar-1…8 的顺序与取值未变', () => {
  const got = [1, 2, 3, 4, 5, 6, 7, 8].map((i) => semantic[`avatar-${i}`].$value);
  assert.deepEqual(got, ['#1E7A68', '#4A5A8A', '#8A6A4A', '#6E4A8A', '#4A7A8A', '#8A4A5A', '#5A8A4A', '#8A7A4A']);
});
