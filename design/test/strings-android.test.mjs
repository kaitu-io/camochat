import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderAndroid } from '../strings/android.mjs';

test('具名占位符按英文顺序编号，中文可倒序', () => {
  const en = { k: '{a} sent {b}' };
  assert.match(renderAndroid({ k: '{b} 来自 {a}' }, en), /<string name="k">%2\$s 来自 %1\$s<\/string>/);
});
test('同名占位符复用同一编号', () => {
  assert.match(renderAndroid({ k: '{a} and {a}' }, { k: '{a} and {a}' }), /%1\$s and %1\$s/);
});
test('count 渲染为 %d', () => {
  const en = { p: { one: '{count} photo', other: '{count} photos' } };
  const xml = renderAndroid(en, en);
  assert.match(xml, /<plurals name="p">\s*<item quantity="one">%1\$d photo<\/item>\s*<item quantity="other">%1\$d photos<\/item>/);
});
test('zh 复数只输出 other', () => {
  const xml = renderAndroid({ p: { other: '{count} 张' } }, { p: { one: '{count} x', other: '{count} xs' } });
  assert.doesNotMatch(xml, /quantity="one"/);
});
test('转义：无占位符保留 % 并标 formatted=false', () => {
  const s = `100% it's "ok" & <b>\nnext`;
  assert.match(renderAndroid({ k: s }, { k: s }), /<string name="k" formatted="false">100% it\\'s \\"ok\\" &amp; &lt;b>\\nnext<\/string>/);
});
test('转义：有占位符时 % 写成 %%', () => {
  assert.match(renderAndroid({ k: '{n} 100%' }, { k: '{n} 100%' }), /%1\$s 100%%/);
});
test('键按字母序、带生成头', () => {
  const xml = renderAndroid({ b: 'B', a: 'A' }, { b: 'B', a: 'A' });
  assert.ok(xml.indexOf('name="a"') < xml.indexOf('name="b"'));
  assert.match(xml, /GENERATED FILE — DO NOT EDIT/);
});
test('首尾空格用 \\u0020 保留', () => {
  const xml = renderAndroid({ k: '[Not uploaded] ' }, { k: '[Not uploaded] ' });
  assert.ok(xml.includes('>[Not uploaded]\\u0020</string>'));
  const lead = renderAndroid({ k: '  x' }, { k: '  x' });
  assert.ok(lead.includes('>\\u0020\\u0020x</string>'));
});
test('{app} 先展开，不成为格式参数', () => {
  const en = { app_name: 'CamoChat', k: 'Hi {app} {n}' };
  const xml = renderAndroid(en, en);
  assert.match(xml, /<string name="k">Hi CamoChat %1\$s<\/string>/);
});
test('en 有而本语言缺的占位符保留 en 编号', () => {
  const xml = renderAndroid({ k: '{b}' }, { k: '{a} {b}' });
  assert.match(xml, /%2\$s/);
});
test('以 @ 或 ? 开头的值转义，输出确定', () => {
  const en = { k: '@me', m: '?x' };
  const a = renderAndroid(en, en);
  assert.ok(a.includes('>\\@me<') && a.includes('>\\?x<'));
  assert.equal(a, renderAndroid(en, en));
});
