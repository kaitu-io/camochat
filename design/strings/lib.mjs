import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export const BANNED = [
  '封缄', '启缄', '密信', '密文', '乱码', '暗号', '对印', '印记', '再次核对',
  '人肉', '已送出', '面对面配对', '删除账号', '删除账户', '微信', 'WeChat',
];

const KEY_RE = /^[a-z][a-z0-9_]*$/;
const LOCALE_NAMES = { en: 'en', zh: 'zh-Hans' };

export function loadLocales(dir) {
  const read = (f) => JSON.parse(readFileSync(join(dir, f), 'utf8'));
  return { en: read('en.json'), zh: read('zh-Hans.json') };
}

export function placeholders(text) {
  const seen = [];
  for (const m of text.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g)) {
    if (m[1] !== 'app' && !seen.includes(m[1])) seen.push(m[1]);
  }
  return seen;
}

const forms = (entry) => (typeof entry === 'string' ? [entry] : Object.values(entry));
const entryPlaceholders = (entry) => {
  const out = [];
  for (const f of forms(entry)) for (const p of placeholders(f)) if (!out.includes(p)) out.push(p);
  return out;
};

export function argOrder(en) {
  return placeholders(typeof en === 'string' ? en : en.other);
}

export function expandApp(locale) {
  const app = locale.app_name;
  if (typeof app !== 'string') return locale;
  const sub = (s) => s.replaceAll('{app}', app);
  const out = {};
  for (const [k, v] of Object.entries(locale)) {
    out[k] = typeof v === 'string' ? sub(v) : Object.fromEntries(Object.entries(v).map(([f, s]) => [f, sub(s)]));
  }
  return out;
}

export function validate(locales) {
  const errs = [];
  const err = (key, problem, loc) => errs.push(`${key}: ${problem} (${LOCALE_NAMES[loc]})`);
  const { en, zh } = locales;

  for (const loc of ['en', 'zh']) {
    for (const key of Object.keys(locales[loc])) {
      if (!KEY_RE.test(key)) err(key, 'invalid key name', loc);
    }
  }
  for (const key of Object.keys(en)) if (!(key in zh)) err(key, 'missing key', 'zh');
  for (const key of Object.keys(zh)) if (!(key in en)) err(key, 'missing key', 'en');

  for (const loc of ['en', 'zh']) {
    for (const [key, v] of Object.entries(locales[loc])) {
      if (typeof v === 'string') continue;
      if (v === null || typeof v !== 'object' || typeof v.other !== 'string') {
        err(key, 'plural must have "other"', loc);
      } else if (loc === 'en' && typeof v.one !== 'string') {
        err(key, 'plural must have "one"', loc);
      } else if (Object.values(v).some((s) => typeof s !== 'string')) {
        err(key, 'plural forms must be strings', loc);
      }
    }
  }

  const wellFormed = (v) => typeof v === 'string' || (v && typeof v === 'object' && Object.values(v).every((s) => typeof s === 'string'));
  for (const key of Object.keys(en)) {
    if (!(key in zh) || !wellFormed(en[key]) || !wellFormed(zh[key])) continue;
    const a = entryPlaceholders(en[key]);
    const b = entryPlaceholders(zh[key]);
    const missingInZh = a.filter((p) => !b.includes(p));
    const extraInZh = b.filter((p) => !a.includes(p));
    if (missingInZh.length) err(key, `missing placeholder {${missingInZh.join('}, {')}}`, 'zh');
    if (extraInZh.length) err(key, `unexpected placeholder {${extraInZh.join('}, {')}}`, 'zh');
  }

  for (const loc of ['en', 'zh']) {
    const expanded = expandApp(locales[loc]);
    for (const [key, v] of Object.entries(expanded)) {
      if (!wellFormed(v)) continue;
      const text = forms(v).join('\n').toLowerCase();
      for (const word of BANNED) {
        if (text.includes(word.toLowerCase())) err(key, `banned word "${word}"`, loc);
      }
    }
  }
  return errs;
}
