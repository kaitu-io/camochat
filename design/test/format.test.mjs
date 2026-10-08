import { test } from 'node:test';
import assert from 'node:assert/strict';
import { camel, pascal, parseHex } from '../build-tokens.mjs';

test('camel/pascal casing', () => {
  assert.equal(camel(['send-green']), 'sendGreen');
  assert.equal(camel(['bubble-self-bg']), 'bubbleSelfBg');
  assert.equal(pascal(['space-l']), 'SpaceL');
});

test('parseHex handles RRGGBB and RRGGBBAA', () => {
  assert.deepEqual(parseHex('#07C160'), { r: 7, g: 193, b: 96, a: 255 });
  assert.deepEqual(parseHex('#000000B8'), { r: 0, g: 0, b: 0, a: 184 });
});
