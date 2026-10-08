import { argOrder, expandApp } from './lib.mjs';

const HEADER = '<!-- GENERATED FILE — DO NOT EDIT. Source: design/strings/*.json. Regenerate: cd design && npm run strings -->';
const PLURAL_ORDER = ['zero', 'one', 'two', 'few', 'many', 'other'];
const PLACEHOLDER_RE = /\{([A-Za-z_][A-Za-z0-9_]*)\}/g;

const escapeText = (s, percent) =>
  s
    .replaceAll('\\', '\\\\')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll("'", "\\'")
    .replaceAll('"', '\\"')
    .replaceAll('\n', '\\n')
    .replaceAll('\t', '\\t')
    .replaceAll('%', percent);

// aapt trims leading/trailing whitespace and treats a leading @ or ? as a reference.
const protect = (s) =>
  s
    .replace(/^ +/, (m) => '\\u0020'.repeat(m.length))
    .replace(/ +$/, (m) => '\\u0020'.repeat(m.length))
    .replace(/^([@?])/, '\\$1');

// Returns { text, hasArgs }. Placeholders get their en-order index; {app} stays literal text.
function convert(value, order) {
  let hasArgs = false;
  let out = '';
  let last = 0;
  const parts = [];
  for (const m of value.matchAll(PLACEHOLDER_RE)) {
    if (m[1] === 'app') continue;
    hasArgs = true;
    parts.push([value.slice(last, m.index), m[1]]);
    last = m.index + m[0].length;
  }
  const tail = value.slice(last);
  const idx = (name) => {
    let i = order.indexOf(name);
    if (i < 0) {
      order.push(name);
      i = order.length - 1;
    }
    return i + 1;
  };
  for (const [text, name] of parts) {
    out += escapeText(text, '%%') + `%${idx(name)}$${name === 'count' ? 'd' : 's'}`;
  }
  out += escapeText(tail, hasArgs ? '%%' : '%');
  return { text: protect(out), hasArgs };
}

export function renderAndroid(locale, en) {
  const loc = expandApp(locale);
  const lines = ['<?xml version="1.0" encoding="utf-8"?>', HEADER, '<resources>'];
  for (const key of Object.keys(loc).sort()) {
    const value = loc[key];
    const order = key in en ? [...argOrder(expandApp({ [key]: en[key] })[key])] : [];
    if (typeof value === 'string') {
      const { text, hasArgs } = convert(value, order);
      const attr = !hasArgs && text.includes('%') ? ' formatted="false"' : '';
      lines.push(`    <string name="${key}"${attr}>${text}</string>`);
    } else {
      lines.push(`    <plurals name="${key}">`);
      const forms = Object.keys(value).sort((a, b) => PLURAL_ORDER.indexOf(a) - PLURAL_ORDER.indexOf(b));
      for (const q of forms) {
        // getQuantityString always formats, so a literal % must be %% even without args.
        const { text } = convert(value[q], [...order]);
        const fixed = text.includes('%') && !/%\d\$/.test(text) ? text.replaceAll('%', '%%') : text;
        lines.push(`        <item quantity="${q}">${fixed}</item>`);
      }
      lines.push('    </plurals>');
    }
  }
  lines.push('</resources>', '');
  return lines.join('\n');
}
