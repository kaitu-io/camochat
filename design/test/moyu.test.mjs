import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveTokens, formatSwiftMoyu, formatKotlinMoyu, formatAndroidColorsXml } from '../build-tokens.mjs';

// 模拟 SD merge 后的 raw tokens(path/value/type/filePath)
const raw = [
  { path: ['jade-600'], value: '#1E7A68', type: 'color', filePath: 'tokens/moyu/primitive.json' },
  { path: ['night-950'], value: '#0B0D0E', type: 'color', filePath: 'tokens/moyu/primitive.json' },
  { path: ['accent-primary'], value: { light: '{jade-600}', dark: '#3FAE8C' }, type: 'color', filePath: 'tokens/moyu/semantic.json' },
  { path: ['avatar-1'], value: '#1E7A68', type: 'color', filePath: 'tokens/moyu/semantic.json' },
  { path: ['space-l'], value: 16, type: 'dimension', filePath: 'tokens/moyu/scale.json' },
  { path: ['radius-bubble-tail'], value: 4, type: 'dimension', filePath: 'tokens/moyu/scale.json' },
  { path: ['size-bubble-max'], value: 300, type: 'dimension', filePath: 'tokens/moyu/scale.json' },
  { path: ['font-size-body'], value: 17, type: 'fontSize', filePath: 'tokens/moyu/scale.json' },
  { path: ['motion-seal'], value: 600, type: 'duration', filePath: 'tokens/moyu/scale.json' },
];

test('resolveTokens: alias 解析 + primitive 过滤', () => {
  const out = resolveTokens(raw);
  // primitive 不出现在输出
  assert.equal(out.find((t) => t.name === 'jade-600'), undefined);
  const accent = out.find((t) => t.name === 'accent-primary');
  assert.deepEqual(accent.value, { light: '#1E7A68', dark: '#3FAE8C' });
  // 单值颜色补齐双轨
  const avatar = out.find((t) => t.name === 'avatar-1');
  assert.deepEqual(avatar.value, { light: '#1E7A68', dark: '#1E7A68' });
});

test('resolveTokens: 未知引用报错', () => {
  assert.throws(() => resolveTokens([
    { path: ['x'], value: { light: '{nope}', dark: '#000000' }, type: 'color', filePath: 'tokens/moyu/semantic.json' },
  ]), /nope/);
});

test('Kotlin Moyu 输出', () => {
  const k = formatKotlinMoyu(resolveTokens(raw));
  assert.match(k, /package app\.chencang\.design/);
  assert.match(k, /class MoyuColorScheme\(/);
  assert.match(k, /val accentPrimary: androidx\.compose\.ui\.graphics\.Color/);
  assert.match(k, /val MoyuLight = MoyuColorScheme\(/);
  assert.match(k, /accentPrimary = androidx\.compose\.ui\.graphics\.Color\(0xFF1E7A68\)/);
  assert.match(k, /val MoyuDark = MoyuColorScheme\(/);
  assert.match(k, /accentPrimary = androidx\.compose\.ui\.graphics\.Color\(0xFF3FAE8C\)/);
  assert.match(k, /object Space \{[\s\S]*val L: Dp = 16\.dp/);
  assert.match(k, /object Radius \{[\s\S]*val BubbleTail: Dp = 4\.dp/);
  assert.match(k, /object Size \{[\s\S]*val BubbleMax: Dp = 300\.dp/);
  assert.match(k, /object FontSize \{[\s\S]*val Body: TextUnit = 17\.sp/);
  assert.match(k, /object Motion \{[\s\S]*const val Seal: Int = 600/);
});

test('Swift Moyu 输出', () => {
  const s = formatSwiftMoyu(resolveTokens(raw));
  assert.match(s, /public enum Moyu/);
  assert.match(s, /public enum Palette/);
  assert.match(s, /static let accentPrimary = moyuDynamic\(light: \(0x1E, 0x7A, 0x68, 1\.0000\), dark: \(0x3F, 0xAE, 0x8C, 1\.0000\)\)/);
  assert.match(s, /public enum Space \{[\s\S]*public static let l: CGFloat = 16/);
  assert.match(s, /public static let bubbleTail: CGFloat = 4/);
  assert.match(s, /public enum FontSize \{[\s\S]*public static let body: CGFloat = 17/);
  assert.match(s, /public enum Motion \{[\s\S]*public static let seal: Double = 600/);
  assert.match(s, /canImport\(UIKit\)/);
});

// ---- Android generated colors.xml (values / values-night) -----------------

test('Android colors.xml: 头部 + light/dark 双轨', () => {
  const resolved = resolveTokens(raw);
  const light = formatAndroidColorsXml(resolved, 'light');
  const dark = formatAndroidColorsXml(resolved, 'dark');

  assert.match(light, /^<\?xml version="1\.0" encoding="utf-8"\?>/);
  assert.match(light, /GENERATED FILE — DO NOT EDIT\. Source: design\/tokens\/moyu\/\*\.json/);
  assert.match(light, /<resources>/);

  // alias 解析 + light/dark 分轨(accent-primary: light={jade-600}, dark 字面 #3FAE8C)
  assert.match(light, /<color name="moyu_accent_primary">#FF1E7A68<\/color>/);
  assert.match(dark, /<color name="moyu_accent_primary">#FF3FAE8C<\/color>/);

  // 单值颜色(无 light/dark 分轨的 semantic token)在两轨输出相同值
  assert.match(light, /<color name="moyu_avatar_1">#FF1E7A68<\/color>/);
  assert.match(dark, /<color name="moyu_avatar_1">#FF1E7A68<\/color>/);
});

// ---- 三 tab 骨架新增 token(读真实 token 文件) ------------------------------
import { readFileSync } from 'node:fs';

test('真实 token:tab 栏容器色、头像字色、头像/图标尺寸', () => {
  const dir = new URL('../tokens/moyu/', import.meta.url);
  const rawFrom = (file) =>
    Object.entries(JSON.parse(readFileSync(new URL(file, dir), 'utf8'))).map(([k, v]) => ({
      path: [k], value: v.$value, type: v.$type, filePath: `tokens/moyu/${file}`,
    }));
  const out = resolveTokens([...rawFrom('primitive.json'), ...rawFrom('semantic.json'), ...rawFrom('scale.json')]);
  const get = (n) => out.find((t) => t.name === n);
  assert.deepEqual(get('accent-container').value, { light: '#D9F0E8', dark: '#1C4438' });
  assert.deepEqual(get('avatar-glyph').value, { light: '#FFFFFF', dark: '#FFFFFF' });
  assert.equal(get('size-tab-icon').value, 24);
  assert.equal(get('size-avatar-list').value, 44);
  assert.equal(get('size-avatar-profile').value, 72);
  assert.equal(get('size-avatar-inline').value, 28);
});
