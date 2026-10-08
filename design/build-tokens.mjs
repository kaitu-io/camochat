import StyleDictionary from 'style-dictionary';

const SWIFT_OUT = '../ios/ChencangShared/Sources/ChencangShared/Generated';

const HEADER = (regenCmd) =>
  `// GENERATED FILE — DO NOT EDIT.\n` +
  `// Source of truth: design/tokens/*.json\n` +
  `// Regenerate: cd design && npm run tokens (${regenCmd})\n`;

// ---- name casing -----------------------------------------------------------
const words = (path) => path.flatMap((s) => String(s).split('-')).filter(Boolean);
export function camel(path) {
  return words(path)
    .map((w, i) => (i === 0 ? w.toLowerCase() : w[0].toUpperCase() + w.slice(1).toLowerCase()))
    .join('');
}
export function pascal(path) {
  const c = camel(path);
  return c[0].toUpperCase() + c.slice(1);
}

// ---- color parsing ---------------------------------------------------------
// Accepts #RGB, #RRGGBB, #RRGGBBAA. Returns {r,g,b,a} as 0..255 ints (a default 255).
export function parseHex(hex) {
  let h = hex.replace('#', '');
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  const a = h.length >= 8 ? parseInt(h.slice(6, 8), 16) : 255;
  return { r, g, b, a };
}
const hex2 = (n) => n.toString(16).toUpperCase().padStart(2, '0');

// ---- Moyu v2: alias resolver -----------------------------------------------
// 自研解析,不依赖 SD 的引用机制(我们的 transforms:[] 哲学:SD 只 merge)。
// primitive.json 里的 token 仅作 alias 目标,不进输出 → UI 物理上引用不到 L1。
const REF_RE = /^\{([a-z0-9-]+)\}$/;

export function resolveTokens(rawTokens) {
  const byName = new Map(rawTokens.map((t) => [t.path.join('-'), t]));
  const lookup = (ref) => {
    const m = REF_RE.exec(ref);
    if (!m) return ref;
    const target = byName.get(m[1]);
    if (!target) throw new Error(`unresolved token reference: ${m[1]}`);
    if (typeof target.value === 'string' && REF_RE.test(target.value)) return lookup(target.value);
    return target.value;
  };
  return rawTokens
    .filter((t) => !String(t.filePath ?? '').includes('primitive'))
    .map((t) => {
      const name = t.path.join('-');
      let value = t.value;
      if (t.type === 'color') {
        if (typeof value === 'object') {
          value = { light: lookup(value.light), dark: lookup(value.dark) };
        } else {
          const v = lookup(value);
          value = { light: v, dark: v };
        }
      }
      return { name, value, type: t.type };
    });
}

// ---- Moyu: routing-completeness guard --------------------------------------
// Both formatters route each token into exactly one of a handful of hardcoded
// buckets (colors / space / radius / size / fontSize / duration) by exact type
// + name-prefix match. A token that doesn't match any bucket would otherwise be
// silently dropped — no error, no CI failure, just a token that's in the JSON
// source but missing from the generated Kotlin/Swift. Fail loudly instead.
function assertTokensRouted(tokens, buckets) {
  const routed = new Set(buckets.flat().map((t) => t.name));
  const orphans = tokens.filter((t) => !routed.has(t.name));
  if (orphans.length > 0) {
    const list = orphans.map((t) => `${t.name} (type=${t.type})`).join(', ');
    throw new Error(`Moyu token codegen: unrouted token(s) not captured by any output group — ${list}`);
  }
}

// ---- Moyu Kotlin format ----------------------------------------------------
const CC = (n) => camel(n.split('-'));   // 'accent-primary' → 'accentPrimary'
const stripGroup = (n, g) => pascal(n.slice(g.length + 1).split('-')); // 'space-l','space' → 'L'
const kColor = (hex) => {
  const { r, g, b, a } = parseHex(hex);
  return `androidx.compose.ui.graphics.Color(0x${hex2(a)}${hex2(r)}${hex2(g)}${hex2(b)})`;
};

export function formatKotlinMoyu(tokens) {
  const colors = tokens.filter((t) => t.type === 'color').sort((a, b) => a.name.localeCompare(b.name));
  const dims = (g) => tokens.filter((t) => t.type === 'dimension' && t.name.startsWith(g + '-')).sort((a, b) => a.name.localeCompare(b.name));
  const fonts = tokens.filter((t) => t.type === 'fontSize').sort((a, b) => a.name.localeCompare(b.name));
  const motions = tokens.filter((t) => t.type === 'duration').sort((a, b) => a.name.localeCompare(b.name));
  assertTokensRouted(tokens, [colors, dims('space'), dims('radius'), dims('size'), fonts, motions]);
  const schemeParams = colors.map((t) => `    val ${CC(t.name)}: androidx.compose.ui.graphics.Color,`);
  const inst = (track) => colors.map((t) => `    ${CC(t.name)} = ${kColor(t.value[track])},`);
  const dimObj = (obj, g) => `    object ${obj} {\n${dims(g).map((t) => `        val ${stripGroup(t.name, g)}: Dp = ${t.value}.dp`).join('\n')}\n    }`;
  return (
    HEADER('Kotlin') +
    `package app.chencang.design\n\n` +
    `import androidx.compose.runtime.Immutable\n` +
    `import androidx.compose.ui.unit.Dp\n` +
    `import androidx.compose.ui.unit.dp\n` +
    `import androidx.compose.ui.unit.TextUnit\n` +
    `import androidx.compose.ui.unit.sp\n\n` +
    `@Immutable\nclass MoyuColorScheme(\n${schemeParams.join('\n')}\n)\n\n` +
    `val MoyuLight = MoyuColorScheme(\n${inst('light').join('\n')}\n)\n\n` +
    `val MoyuDark = MoyuColorScheme(\n${inst('dark').join('\n')}\n)\n\n` +
    `object Moyu {\n` +
    dimObj('Space', 'space') + '\n' +
    dimObj('Radius', 'radius') + '\n' +
    dimObj('Size', 'size') + '\n' +
    `    object FontSize {\n${fonts.map((t) => `        val ${stripGroup(t.name, 'font-size')}: TextUnit = ${t.value}.sp`).join('\n')}\n    }\n` +
    `    object Motion {\n${motions.map((t) => `        const val ${stripGroup(t.name, 'motion')}: Int = ${t.value}`).join('\n')}\n    }\n` +
    `}\n`
  );
}

// ---- Moyu Swift format -----------------------------------------------------
const sTuple = (hex) => {
  const { r, g, b, a } = parseHex(hex);
  return `(0x${hex2(r)}, 0x${hex2(g)}, 0x${hex2(b)}, ${(a / 255).toFixed(4)})`;
};

export function formatSwiftMoyu(tokens) {
  const colors = tokens.filter((t) => t.type === 'color').sort((a, b) => a.name.localeCompare(b.name));
  const dims = (g) => tokens.filter((t) => t.type === 'dimension' && t.name.startsWith(g + '-')).sort((a, b) => a.name.localeCompare(b.name));
  const fonts = tokens.filter((t) => t.type === 'fontSize').sort((a, b) => a.name.localeCompare(b.name));
  const motions = tokens.filter((t) => t.type === 'duration').sort((a, b) => a.name.localeCompare(b.name));
  assertTokensRouted(tokens, [colors, dims('space'), dims('radius'), dims('size'), fonts, motions]);
  const camelStrip = (n, g) => camel(n.slice(g.length + 1).split('-'));
  const dimEnum = (obj, g) => `    public enum ${obj} {\n${dims(g).map((t) => `        public static let ${camelStrip(t.name, g)}: CGFloat = ${t.value}`).join('\n')}\n    }`;
  return (
    HEADER('Swift') +
    `import SwiftUI\nimport CoreGraphics\n#if canImport(UIKit)\nimport UIKit\n#endif\n\n` +
    `public enum Moyu {\n` +
    `    public enum Palette {\n${colors.map((t) => `        public static let ${CC(t.name)} = moyuDynamic(light: ${sTuple(t.value.light)}, dark: ${sTuple(t.value.dark)})`).join('\n')}\n    }\n` +
    dimEnum('Space', 'space') + '\n' +
    dimEnum('Radius', 'radius') + '\n' +
    dimEnum('Size', 'size') + '\n' +
    `    public enum FontSize {\n${fonts.map((t) => `        public static let ${camelStrip(t.name, 'font-size')}: CGFloat = ${t.value}`).join('\n')}\n    }\n` +
    `    public enum Motion {\n${motions.map((t) => `        public static let ${camelStrip(t.name, 'motion')}: Double = ${t.value}`).join('\n')}\n    }\n` +
    `}\n\n` +
    `// (r, g, b, alpha) 0-255 通道 + 0-1 alpha;dark 经 UIColor dynamic provider,\n` +
    `// macOS(swift test)上无 UIKit → 回退 light 单值。\n` +
    `private typealias MoyuRGBA = (UInt8, UInt8, UInt8, Double)\n` +
    `private func moyuColor(_ c: MoyuRGBA) -> SwiftUI.Color {\n` +
    `    SwiftUI.Color(red: Double(c.0) / 255.0, green: Double(c.1) / 255.0, blue: Double(c.2) / 255.0, opacity: c.3)\n` +
    `}\n` +
    `private func moyuDynamic(light: MoyuRGBA, dark: MoyuRGBA) -> SwiftUI.Color {\n` +
    `    #if canImport(UIKit)\n` +
    `    return SwiftUI.Color(UIColor { tc in\n` +
    `        let c = tc.userInterfaceStyle == .dark ? dark : light\n` +
    `        return UIColor(red: CGFloat(c.0) / 255.0, green: CGFloat(c.1) / 255.0, blue: CGFloat(c.2) / 255.0, alpha: CGFloat(c.3))\n` +
    `    })\n` +
    `    #else\n` +
    `    return moyuColor(light)\n` +
    `    #endif\n` +
    `}\n`
  );
}

// ---- Android generated colors.xml (values / values-night) ------------------
// This feeds `themes.xml` and any future XML resource that needs a themed
// color without hand-writing hex. `track` selects light vs. dark output;
// same source tokens, two destination directories (values / values-night) so
// Android's resource-qualifier resolution picks the right one automatically.
export function formatAndroidColorsXml(tokens, track) {
  const colors = tokens.filter((t) => t.type === 'color')
    .sort((a, b) => a.name.localeCompare(b.name));
  const line = (t) => `    <color name="moyu_${t.name.replace(/-/g, '_')}">#${(() => { const { r, g, b, a } = parseHex(t.value[track]); return hex2(a) + hex2(r) + hex2(g) + hex2(b); })()}</color>`;
  return `<?xml version="1.0" encoding="utf-8"?>\n<!-- GENERATED FILE — DO NOT EDIT. Source: design/tokens/moyu/*.json -->\n<resources>\n${colors.map(line).join('\n')}\n</resources>\n`;
}

// ---- Style Dictionary glue --------------------------------------------------
// DTCG tokens expose $value/$type. We do our OWN value conversion in the formats,
// so we want NO value-mutating transforms (the built-in 'js' group runs size/rem,
// which would turn 4 → 0.25). Use transforms: [] and read raw resolved values.
const moyuToTokens = (dictionary) =>
  resolveTokens(dictionary.allTokens.map((t) => ({ path: t.path, value: t.$value ?? t.value, type: t.$type ?? t.type, filePath: t.filePath })));
StyleDictionary.registerFormat({ name: 'cc/swift-moyu', format: ({ dictionary }) => formatSwiftMoyu(moyuToTokens(dictionary)) });
StyleDictionary.registerFormat({ name: 'cc/kotlin-moyu', format: ({ dictionary }) => formatKotlinMoyu(moyuToTokens(dictionary)) });
StyleDictionary.registerFormat({ name: 'cc/xml-moyu-colors-light', format: ({ dictionary }) => formatAndroidColorsXml(moyuToTokens(dictionary), 'light') });
StyleDictionary.registerFormat({ name: 'cc/xml-moyu-colors-dark', format: ({ dictionary }) => formatAndroidColorsXml(moyuToTokens(dictionary), 'dark') });

function moyuInstance(platformSource, platformName, buildPath, destination, formatName) {
  return new StyleDictionary({
    usesDtcg: true,
    source: [
      'tokens/moyu/primitive.json',
      'tokens/moyu/semantic.json',
      'tokens/moyu/scale.json',
      platformSource,
    ],
    platforms: {
      [platformName]: { transforms: [], buildPath: buildPath + '/', files: [{ destination, format: formatName }] },
    },
    log: { verbosity: 'default' },
  });
}

// Import guard: only build when run directly (`node build-tokens.mjs`),
// so test files can import the pure format functions without side effects.
if (import.meta.url === `file://${process.argv[1]}`) {
  const moyuIos = moyuInstance('tokens/moyu/platform/ios.json', 'swift-moyu', SWIFT_OUT, 'Moyu.swift', 'cc/swift-moyu');
  const moyuAndroid = moyuInstance('tokens/moyu/platform/android.json', 'compose-moyu', '../android/design/src/main/kotlin/app/chencang/design', 'Moyu.kt', 'cc/kotlin-moyu');
  await moyuIos.buildAllPlatforms();
  await moyuAndroid.buildAllPlatforms();
  console.log('✓ moyu tokens generated → Moyu.swift + Moyu.kt');

  const moyuColorsLight = moyuInstance('tokens/moyu/platform/android.json', 'xml-moyu-colors-light', '../android/app/src/main/res/values', 'moyu_colors.xml', 'cc/xml-moyu-colors-light');
  const moyuColorsDark = moyuInstance('tokens/moyu/platform/android.json', 'xml-moyu-colors-dark', '../android/app/src/main/res/values-night', 'moyu_colors.xml', 'cc/xml-moyu-colors-dark');
  await moyuColorsLight.buildAllPlatforms();
  await moyuColorsDark.buildAllPlatforms();
  console.log('✓ moyu android color resources generated → values/moyu_colors.xml + values-night/moyu_colors.xml');
}
