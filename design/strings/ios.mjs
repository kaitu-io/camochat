import { argOrder, expandApp } from './lib.mjs';

const HEADER_TEXT = 'GENERATED FILE — DO NOT EDIT. Source: design/strings/*.json. Regenerate: cd design && npm run strings';
const PLACEHOLDER_RE = /\{([A-Za-z_][A-Za-z0-9_]*)\}/g;
const PLURAL_ORDER = ['zero', 'one', 'two', 'few', 'many', 'other'];

const INFOPLIST = {
  app: {
    infoplist_app_display_name: 'CFBundleDisplayName',
    infoplist_microphone_usage: 'NSMicrophoneUsageDescription',
    infoplist_camera_usage: 'NSCameraUsageDescription',
    infoplist_photos_usage: 'NSPhotoLibraryAddUsageDescription',
  },
  action: {
    infoplist_action_display_name: 'CFBundleDisplayName',
  },
};

const SWIFT_KEYWORDS = new Set([
  'associatedtype', 'class', 'deinit', 'enum', 'extension', 'fileprivate', 'func', 'import', 'init', 'inout',
  'internal', 'let', 'open', 'operator', 'private', 'precedencegroup', 'protocol', 'public', 'rethrows', 'static',
  'struct', 'subscript', 'typealias', 'var', 'break', 'case', 'catch', 'continue', 'default', 'defer', 'do', 'else',
  'fallthrough', 'for', 'guard', 'if', 'in', 'repeat', 'return', 'throw', 'switch', 'where', 'while', 'Any', 'as',
  'false', 'is', 'nil', 'self', 'Self', 'super', 'throws', 'true', 'try',
]);

const isInfoPlist = (key) => key.startsWith('infoplist_');
const isPlural = (v) => typeof v !== 'string';

const escapeStrings = (s) =>
  s.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('\n', '\\n').replaceAll('\t', '\\t');
const escapeXml = (s) => s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

// Converts {name} to positional specifiers; names missing from `order` are appended.
function convert(value, order) {
  const idx = (name) => {
    let i = order.indexOf(name);
    if (i < 0) {
      order.push(name);
      i = order.length - 1;
    }
    return i + 1;
  };
  let out = '';
  let last = 0;
  for (const m of value.matchAll(PLACEHOLDER_RE)) {
    out += value.slice(last, m.index) + `%${idx(m[1])}$${m[1] === 'count' ? 'ld' : '@'}`;
    last = m.index + m[0].length;
  }
  return out + value.slice(last);
}

// A literal % in text must be %% once the string goes through String(format:).
const convertText = (value, order, alwaysFormatted = false) => {
  const hasArgs = alwaysFormatted || PLACEHOLDER_RE.test(value);
  PLACEHOLDER_RE.lastIndex = 0;
  const parts = value.split(PLACEHOLDER_RE);
  // split with a capture group alternates text, name, text, ...
  let out = '';
  for (let i = 0; i < parts.length; i += 2) {
    out += hasArgs ? parts[i].replaceAll('%', '%%') : parts[i];
    if (i + 1 < parts.length) out += convert(`{${parts[i + 1]}}`, order);
  }
  return out;
};

// A plural whose only argument is {count} uses plain %ld; otherwise positional.
const pluralText = (text, order) => {
  const out = convertText(text, [...order], true);
  return order.length === 1 && order[0] === 'count' ? out.replaceAll('%1$ld', '%ld') : out;
};

const enOrder = (en, key) => (key in en ? [...argOrder(expandApp({ [key]: en[key] })[key])] : []);

export function renderStrings(locale, en) {
  const loc = expandApp(locale);
  const lines = [`/* ${HEADER_TEXT} */`];
  for (const key of Object.keys(loc).sort()) {
    if (isInfoPlist(key) || isPlural(loc[key])) continue;
    lines.push(`"${key}" = "${escapeStrings(convertText(loc[key], enOrder(en, key)))}";`);
  }
  lines.push('');
  return lines.join('\n');
}

export function renderStringsdict(locale, en) {
  const loc = expandApp(locale);
  const out = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<!-- ${HEADER_TEXT} -->`,
    '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
    '<plist version="1.0">',
    '<dict>',
  ];
  for (const key of Object.keys(loc).sort()) {
    const value = loc[key];
    if (isInfoPlist(key) || !isPlural(value)) continue;
    const order = enOrder(en, key);
    out.push(
      `    <key>${key}</key>`,
      '    <dict>',
      '        <key>NSStringLocalizedFormatKey</key>',
      `        <string>${order.length > 1 && order.includes('count') ? `%${order.indexOf('count') + 1}$#@v@` : '%#@v@'}</string>`,
      '        <key>v</key>',
      '        <dict>',
      '            <key>NSStringFormatSpecTypeKey</key>',
      '            <string>NSStringPluralRuleType</string>',
      '            <key>NSStringFormatValueTypeKey</key>',
      '            <string>ld</string>',
    );
    const forms = Object.keys(value).sort((a, b) => PLURAL_ORDER.indexOf(a) - PLURAL_ORDER.indexOf(b));
    for (const q of forms) {
      out.push(`            <key>${q}</key>`, `            <string>${escapeXml(pluralText(value[q], order))}</string>`);
    }
    out.push('        </dict>', '    </dict>');
  }
  out.push('</dict>', '</plist>', '');
  return out.join('\n');
}

export function renderInfoPlist(locale, target) {
  const loc = expandApp(locale);
  const map = INFOPLIST[target];
  if (!map) throw new Error(`unknown InfoPlist target: ${target}`);
  const lines = [`/* ${HEADER_TEXT} */`];
  for (const key of Object.keys(map).sort()) {
    if (typeof loc[key] !== 'string') continue;
    lines.push(`"${map[key]}" = "${escapeStrings(loc[key])}";`);
  }
  lines.push('');
  return lines.join('\n');
}

const camel = (key) => key.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase());
const esc = (id) => (SWIFT_KEYWORDS.has(id) ? `\`${id}\`` : id);
const swiftName = (key) => esc(camel(key));
const swiftString = (s) => s.replaceAll('\\', '\\\\').replaceAll('"', '\\"');

export function renderL10n(en) {
  const lines = [
    `// ${HEADER_TEXT}`,
    'import Foundation',
    '',
    'public enum L10n {',
  ];
  const keys = Object.keys(en).filter((k) => !isInfoPlist(k)).sort();
  keys.forEach((key, i) => {
    const args = argOrder(en[key]);
    const fmt = `NSLocalizedString("${swiftString(key)}", bundle: .module, comment: "")`;
    const name = swiftName(key);
    if (args.length === 0) {
      lines.push(`    public static var ${name}: String {`, `        ${fmt}`, '    }');
    } else {
      const params = args.map((a, j) => `${j === 0 ? '_ ' : ''}${esc(a)}: ${a === 'count' ? 'Int' : 'String'}`);
      lines.push(
        `    public static func ${name}(${params.join(', ')}) -> String {`,
        `        String(format: ${fmt}, locale: .current, ${args.map(esc).join(', ')})`,
        '    }',
      );
    }
    if (i < keys.length - 1) lines.push('');
  });
  lines.push('}', '');
  return lines.join('\n');
}
