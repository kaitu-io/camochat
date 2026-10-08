// Generates the iOS + Android string resources from design/strings/{en,zh-Hans}.json.
// Usage: node build-strings.mjs [--src <dir>] [--out <repo-root>]
// Validation errors are all printed and the script exits 1 before writing anything.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadLocales, validate } from './strings/lib.mjs';
import { renderAndroid } from './strings/android.mjs';
import { renderStrings, renderStringsdict, renderInfoPlist, renderL10n } from './strings/ios.mjs';

const here = dirname(fileURLToPath(import.meta.url));

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  if (i < 0) return fallback;
  const v = process.argv[i + 1];
  if (!v) {
    console.error(`${name} needs a value`);
    process.exit(1);
  }
  return resolve(v);
}

const src = arg('--src', join(here, 'strings'));
const out = arg('--out', resolve(here, '..'));

const locales = loadLocales(src);
const errors = validate(locales);
if (errors.length) {
  console.error(`design/strings: ${errors.length} error(s):`);
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}

const { en, zh } = locales;
const SHARED = 'ios/ChencangShared/Sources/ChencangShared';
const files = {
  'android/shared/src/main/res/values/strings.xml': renderAndroid(en, en),
  'android/shared/src/main/res/values-zh/strings.xml': renderAndroid(zh, en),
  [`${SHARED}/L10n/L10n.swift`]: renderL10n(en),
};
for (const [lproj, locale] of [['en.lproj', en], ['zh-Hans.lproj', zh]]) {
  files[`${SHARED}/Resources/${lproj}/Localizable.strings`] = renderStrings(locale, en);
  files[`${SHARED}/Resources/${lproj}/Localizable.stringsdict`] = renderStringsdict(locale, en);
  files[`ios/ChencangCompanion/Resources/${lproj}/InfoPlist.strings`] = renderInfoPlist(locale, 'app');
  files[`ios/ChencangAction/Resources/${lproj}/InfoPlist.strings`] = renderInfoPlist(locale, 'action');
}

for (const [rel, content] of Object.entries(files).sort(([a], [b]) => a.localeCompare(b))) {
  const path = join(out, rel);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}
console.log(`design/strings: wrote ${Object.keys(files).length} files under ${out}`);
