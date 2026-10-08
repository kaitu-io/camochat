import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkSource } from '../scripts/lint-colors.mjs';

test('Kotlin 硬编码颜色被抓', () => {
  const v = checkSource('val bad = Color(0xFF95EC69)', 'A.kt');
  assert.equal(v.length, 1);
});
test('Swift 硬编码颜色被抓', () => {
  assert.equal(checkSource('let bad = Color(red: 0.5, green: 0.1, blue: 0.2)', 'B.swift').length, 1);
  assert.equal(checkSource('Color(hex: "#95EC69")', 'C.swift').length, 1);
});
test('token 引用与生成文件放行', () => {
  assert.equal(checkSource('val ok = moyuColors.accentPrimary', 'A.kt').length, 0);
  assert.equal(checkSource('val gen = Color(0xFF95EC69)', 'generated/Whatever.kt').length, 0);
  assert.equal(checkSource('let gen = SwiftUI.Color(red: 0.1, green: 0.2, blue: 0.3, opacity: 1)', 'Generated/Moyu.swift').length, 0);
});
test('Moyu.kt/Moyu.swift 按文件名放行(即使不在 generated/ 目录段下)', () => {
  assert.equal(
    checkSource(
      'accentPrimary = Color(0xFF1E7A68)',
      'android/design/src/main/kotlin/app/chencang/design/Moyu.kt',
    ).length,
    0,
  );
  assert.equal(
    checkSource(
      'let accentPrimary = SwiftUI.Color(red: 0.1, green: 0.2, blue: 0.3, opacity: 1)',
      'ios/ChencangShared/Sources/ChencangShared/Generated/Moyu.swift',
    ).length,
    0,
  );
});
test('同目录下手写文件不因目录豁免——只豁免 Moyu.kt/Moyu.swift 本身', () => {
  assert.equal(
    checkSource(
      'val bad = Color(0xFF1E7A68)',
      'android/design/src/main/kotlin/app/chencang/design/MoyuTheme.kt',
    ).length,
    1,
  );
});
test('XML 硬编码色值被抓(标签内容 + 属性值两种写法)', () => {
  assert.equal(
    checkSource(
      '<item name="android:windowBackground">#F7F7F5</item>',
      'android/app/src/main/res/values/themes.xml',
    ).length,
    1,
  );
  assert.equal(
    checkSource(
      'android:fillColor="#FFFFFFFF"',
      'android/app/src/main/res/drawable/some_icon.xml',
    ).length,
    1,
  );
});

test('生成的 moyu_colors.xml 按文件名放行(values/ 与 values-night/ 两份)', () => {
  assert.equal(
    checkSource(
      '<color name="moyu_surface_base">#FFF7F7F5</color>',
      'android/app/src/main/res/values/moyu_colors.xml',
    ).length,
    0,
  );
  assert.equal(
    checkSource(
      '<color name="moyu_surface_base">#FF101214</color>',
      'android/app/src/main/res/values-night/moyu_colors.xml',
    ).length,
    0,
  );
});

test('launcher icon 资源按精确路径放行,同名文件不因文件名兜底豁免', () => {
  assert.equal(
    checkSource(
      '<color name="ic_launcher_background">#0F172A</color>',
      'android/app/src/main/res/values/colors.xml',
    ).length,
    0,
  );
  assert.equal(
    checkSource(
      'android:fillColor="#FFFFFFFF"',
      'android/app/src/main/res/drawable/ic_launcher_foreground.xml',
    ).length,
    0,
  );
  // 同名 colors.xml 若出现在别的模块(非真实启动器图标资源),不该被文件名兜底放行——
  // 豁免按精确路径,不按 basename。
  assert.equal(
    checkSource(
      '<color name="x">#123456</color>',
      'android/design/src/main/res/values/colors.xml',
    ).length,
    1,
  );
});
