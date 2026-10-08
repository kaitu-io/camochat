#!/usr/bin/env node
// UI 源码硬编码颜色守卫。生成目录(generated/Generated)及已知生成文件(Moyu.kt/Moyu.swift)豁免;
// 尺寸/间距不在此 lint 范围(噪音太大),靠 review + token 可用性。
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

const PATTERNS = [
  { re: /Color\(0x[0-9A-Fa-f]{6,8}\)/, why: 'Compose 十六进制颜色构造' },
  { re: /Color\(red:\s*[\d.]+,\s*green:/, why: 'SwiftUI RGB 颜色构造' },
  { re: /["']#[0-9A-Fa-f]{6}["']/, why: '十六进制色串' },
];
// Android resource XML 里的颜色字面值既不带引号包裹的方式与 kt/swift 一致(属性值用引号,
// 但标签内容 `>#RRGGBB<` 不用),也可能是 3/4/6/8 位——用一条通用规则覆盖两种写法。
const XML_PATTERNS = [{ re: /#[0-9A-Fa-f]{3,8}/, why: 'XML 硬编码色值' }];
const EXEMPT = /(generated|Generated)\//;
// android/design/.../Moyu.kt 与 ios .../Generated/Moyu.swift 是生成文件,但 Android 侧
// 刻意不放在 generated/ 目录段下(见 task-4-brief 讨论),按文件名兜底豁免。
// moyu_colors.xml(values/ 与 values-night/ 两份)同理:生成物,不放在 generated/ 段下。
const EXEMPT_FILENAME = /\/(Moyu\.kt|Moyu\.swift|moyu_colors\.xml)$/;
// launcher icon 资源:Android adaptive-icon 背景色/前景色是平台规范要求的固定字面值,
// 不随 app 主题切换、早于 design token 体系存在,且是像素资产而非 UI 颜色决策——
// 不接入 moyu 生成链,按路径豁免并留痕说明理由。
const EXEMPT_LAUNCHER_ICON = /android\/app\/src\/main\/res\/(values\/colors\.xml|drawable\/ic_launcher_foreground\.xml)$/;

export function checkSource(text, file) {
  if (EXEMPT.test(file) || EXEMPT_FILENAME.test(file) || EXEMPT_LAUNCHER_ICON.test(file)) return [];
  const patterns = file.endsWith('.xml') ? XML_PATTERNS : PATTERNS;
  const out = [];
  text.split('\n').forEach((line, i) => {
    for (const p of patterns) {
      if (p.re.test(line)) out.push({ file, line: i + 1, why: p.why, text: line.trim() });
    }
  });
  return out;
}

// 手写递归 walk(不依赖 node22 globSync)
function* walk(dir, exts) {
  for (const e of readdirSync(dir)) {
    const p = path.join(dir, e);
    const s = statSync(p);
    if (s.isDirectory()) yield* walk(p, exts);
    else if (exts.some((x) => p.endsWith(x))) yield p;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const root = path.resolve(process.cwd(), '..');
  const targets = [
    [path.join(root, 'android/app/src/main/kotlin'), ['.kt']],
    [path.join(root, 'android/design/src/main/kotlin'), ['.kt']],
    [path.join(root, 'android/app/src/main/res'), ['.xml']],
    [path.join(root, 'ios/ChencangCompanion'), ['.swift']],
    [path.join(root, 'ios/ChencangShared/Sources'), ['.swift']],
  ];
  const all = [];
  for (const [dir, exts] of targets) {
    for (const f of walk(dir, exts)) all.push(...checkSource(readFileSync(f, 'utf8'), path.relative(root, f)));
  }
  if (all.length) {
    console.error(`✗ ${all.length} 处硬编码颜色(用 design token 替代):`);
    for (const v of all) console.error(`  ${v.file}:${v.line} [${v.why}] ${v.text}`);
    process.exit(1);
  }
  console.log('✓ lint-colors: no hardcoded colors');
}
