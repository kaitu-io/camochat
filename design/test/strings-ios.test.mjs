import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderStrings, renderStringsdict, renderInfoPlist, renderL10n } from '../strings/ios.mjs';

test('iOS 字符串参数为 %N$@，count 为 %N$ld，中文可倒序', () => {
  const en = { k: '{a} sent {b}' };
  assert.match(renderStrings({ k: '{b} 来自 {a}' }, en), /"k" = "%2\$@ 来自 %1\$@";/);
});
test('.strings 转义引号、反斜杠、换行', () => {
  const s = 'say "hi" \\ ok\nnext';
  assert.match(renderStrings({ k: s }, { k: s }), /"k" = "say \\"hi\\" \\\\ ok\\nnext";/);
});
test('复数进 stringsdict，不进 strings', () => {
  const en = { p: { one: '{count} photo', other: '{count} photos' } };
  assert.doesNotMatch(renderStrings(en, en), /"p"/);
  const d = renderStringsdict(en, en);
  assert.match(d, /<key>p<\/key>/);
  assert.match(d, /<key>NSStringFormatValueTypeKey<\/key>\s*<string>ld<\/string>/);
  assert.match(d, /<key>one<\/key>\s*<string>%ld photo<\/string>/);
});
test('InfoPlist 按 target 取键', () => {
  const loc = { infoplist_app_display_name: '陈仓', infoplist_action_display_name: '陈仓解密' };
  assert.match(renderInfoPlist(loc, 'app'), /"CFBundleDisplayName" = "陈仓";/);
  assert.match(renderInfoPlist(loc, 'action'), /"CFBundleDisplayName" = "陈仓解密";/);
});
test('L10n：无参为 static var，有参为 static func，count 为 Int', () => {
  const swift = renderL10n({ status_shared: 'Shared', card_send_to: 'To {name}', media_photo_count: { one: '{count} photo', other: '{count} photos' } });
  assert.match(swift, /public static var statusShared: String/);
  assert.match(swift, /public static func cardSendTo\(_ name: String\) -> String/);
  assert.match(swift, /public static func mediaPhotoCount\(_ count: Int\) -> String/);
  assert.match(swift, /bundle: \.module/);
  assert.doesNotMatch(swift, /infoplist/i);
});
test('{app} 展开、不编号；infoplist 键不进 Localizable；照片键映射 NSPhotoLibraryAddUsageDescription', () => {
  const loc = { app_name: '陈仓', k: '用 {app} 发给 {name}', infoplist_photos_usage: '存图', infoplist_app_display_name: '{app}' };
  const s = renderStrings(loc, loc);
  assert.match(s, /"k" = "用 陈仓 发给 %1\$@";/);
  assert.doesNotMatch(s, /infoplist/);
  assert.match(renderInfoPlist(loc, 'app'), /"NSPhotoLibraryAddUsageDescription" = "存图";/);
  assert.match(renderInfoPlist(loc, 'app'), /"CFBundleDisplayName" = "陈仓";/);
});
test('stringsdict 只含该语言有的形式，XML 转义，保留其它占位符', () => {
  const en = { p: { one: '{name}: {count} <1>', other: '{name}: {count} <n>' } };
  const d = renderStringsdict({ p: { other: '{count} 张 & {name}' } }, en);
  assert.doesNotMatch(d, /<key>one<\/key>/);
  assert.match(d, /<string>%2\$ld 张 &amp; %1\$@<\/string>/);
  assert.match(renderStringsdict(en, en), /%1\$@: %2\$ld &lt;1&gt;/);
  assert.match(renderStringsdict(en, en), /<string>%2\$#@v@<\/string>/);
  const solo = { q: { one: '{count} a', other: '{count} b' } };
  assert.match(renderStringsdict(solo, solo), /<string>%#@v@<\/string>/);
});
test('L10n：关键字加反引号，确定性输出', () => {
  const en = { default: 'x', zeta: 'z', alpha: 'a' };
  const swift = renderL10n(en);
  assert.match(swift, /public static var `default`: String/);
  assert.ok(swift.indexOf('alpha') < swift.indexOf('zeta'));
  assert.equal(swift, renderL10n(en));
});
test('L10n：关键字参数名同样加反引号', () => {
  const swift = renderL10n({ k: '{in} and {default}' });
  assert.match(swift, /func k\(_ `in`: String, default: String\)|func k\(_ `in`: String, `default`: String\)/);
  assert.match(swift, /locale: \.current, `in`, `default`\)/);
});
test('stringsdict 无占位符的形式也转义 %', () => {
  const en = { p: { one: '{count} x', other: '{count} xs' } };
  assert.match(renderStringsdict({ p: { other: '100% 张' } }, en), /<string>100%% 张<\/string>/);
});
