import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, existsSync, readdirSync, readFileSync, statSync, writeFileSync, cpSync } from 'node:fs';
import { join, relative } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync, spawnSync } from 'node:child_process';
import { loadLocales, placeholders, validate, argOrder, expandApp } from '../strings/lib.mjs';

function snapshot(dir) {
  const out = {};
  const walk = (d) => {
    for (const name of readdirSync(d).sort()) {
      const p = join(d, name);
      if (statSync(p).isDirectory()) walk(p);
      else out[relative(dir, p)] = readFileSync(p, 'utf8');
    }
  };
  walk(dir);
  return out;
}

test('placeholders 按首次出现去重', () => {
  assert.deepEqual(placeholders('{name} 给 {count} 个 {name}'), ['name', 'count']);
});
test('缺键报错并点名', () => {
  const errs = validate({ en: { a: 'x', b: 'y' }, zh: { a: '甲' } });
  assert.ok(errs.some((e) => e.includes('b') && e.includes('zh-Hans')));
});
test('占位符集合不一致报错', () => {
  const errs = validate({ en: { k: 'Hi {name}' }, zh: { k: '你好' } });
  assert.ok(errs.some((e) => e.includes('k') && e.includes('name')));
});
test('禁用词报错', () => {
  const errs = validate({ en: { k: 'Sealed' }, zh: { k: '已封缄' } });
  assert.ok(errs.some((e) => e.includes('封缄')));
});
test('{app} 不算占位符，展开后计入禁用词检查', () => {
  assert.deepEqual(validate({ en: { app_name: 'CamoChat', k: 'Open {app}' }, zh: { app_name: '陈仓', k: '打开{app}' } }), []);
  assert.deepEqual(placeholders('{app} {name}'), ['name']);
  const errs = validate({ en: { app_name: 'WeChat', k: 'Open {app}' }, zh: { app_name: '陈仓', k: '打开{app}' } });
  assert.ok(errs.some((e) => e.includes('WeChat')));
});
test('expandApp 按语言展开', () => {
  assert.equal(expandApp({ app_name: '陈仓', k: '用{app}解密' }).k, '用陈仓解密');
});
test('expandApp 展开复数对象；无 app_name 原样返回', () => {
  const out = expandApp({ app_name: 'X', p: { one: '{app} 1', other: '{app} n' } });
  assert.deepEqual(out.p, { one: 'X 1', other: 'X n' });
  const partial = { k: '{app} hi' };
  assert.deepEqual(expandApp(partial), partial);
});
test('argOrder 以英文顺序为准，复数取 other', () => {
  assert.deepEqual(argOrder('To {name} {link}'), ['name', 'link']);
  assert.deepEqual(argOrder({ one: '{count} photo', other: '{count} photos' }), ['count']);
});
test('非法键名报错', () => {
  assert.ok(validate({ en: { 'Bad-Key': 'x' }, zh: { 'Bad-Key': 'x' } }).length > 0);
});
test('zh 复数只给 other 合法', () => {
  assert.deepEqual(validate({ en: { p: { one: '{count} photo', other: '{count} photos' } }, zh: { p: { other: '{count} 张图片' } } }), []);
});
test('复数结构非法报错', () => {
  assert.ok(validate({ en: { p: { other: '{count} photos' } }, zh: { p: { other: '{count} 张' } } }).some((e) => e.includes('one') && e.includes('en')));
  assert.ok(validate({ en: { p: { one: 'a', other: 'b' } }, zh: { p: { one: 'a' } } }).some((e) => e.includes('other') && e.includes('zh-Hans')));
});
test('仓库里的源文件通过校验', () => {
  assert.deepEqual(validate(loadLocales(new URL('../strings/', import.meta.url).pathname)), []);
});

test('build-strings 在临时目录生成全部文件且两次结果相同', async () => {
  const out = mkdtempSync(join(tmpdir(), 'str-'));
  const run = () => execFileSync('node', ['build-strings.mjs', '--out', out], { cwd: new URL('..', import.meta.url).pathname });
  run(); const first = snapshot(out); run();
  assert.deepEqual(snapshot(out), first);
  for (const p of [
    'android/shared/src/main/res/values/strings.xml',
    'android/shared/src/main/res/values-zh/strings.xml',
    'ios/ChencangShared/Sources/ChencangShared/Resources/en.lproj/Localizable.strings',
    'ios/ChencangShared/Sources/ChencangShared/Resources/zh-Hans.lproj/Localizable.stringsdict',
    'ios/ChencangShared/Sources/ChencangShared/L10n/L10n.swift',
    'ios/ChencangCompanion/Resources/zh-Hans.lproj/InfoPlist.strings',
    'ios/ChencangAction/Resources/en.lproj/InfoPlist.strings',
  ]) assert.ok(existsSync(join(out, p)), p);
});
test('build-strings 校验失败时列出全部错误、退出码 1、不写文件', () => {
  const src = mkdtempSync(join(tmpdir(), 'str-src-'));
  cpSync(new URL('../strings/', import.meta.url).pathname, src, { recursive: true });
  const en = JSON.parse(readFileSync(join(src, 'en.json'), 'utf8'));
  en.common_done = 'WeChat';
  en.extra_only_en = 'x';
  writeFileSync(join(src, 'en.json'), JSON.stringify(en));
  const out = mkdtempSync(join(tmpdir(), 'str-out-'));
  const r = spawnSync('node', ['build-strings.mjs', '--src', src, '--out', out], { cwd: new URL('..', import.meta.url).pathname, encoding: 'utf8' });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /common_done/);
  assert.match(r.stderr, /extra_only_en/);
  assert.deepEqual(readdirSync(out), []);
});
