import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findHanLiterals } from './check-ui-literals.mjs';

test('flags a Han string literal', () => {
  assert.deepEqual(findHanLiterals('val a = "你好"'), [{ line: 1, text: '"你好"' }]);
});

test('ignores line comments, block comments and kdoc', () => {
  assert.deepEqual(findHanLiterals('// 你好\n/* 你好 */\n/** 你好 */'), []);
});

test('does not cut a string at an embedded double slash', () => {
  assert.equal(findHanLiterals('val u = "https://x/中"').length, 1);
});

test('handles escaped quotes and templates', () => {
  assert.equal(findHanLiterals('"a\\"b${x}中"').length, 1);
  assert.equal(findHanLiterals('"${"中"}"').length, 1);
});

test('handles raw strings spanning lines', () => {
  const r = findHanLiterals('val r = """\nline 中\n"""');
  assert.equal(r.length, 1);
  assert.equal(r[0].line, 1);
});

test('flags a Han char literal', () => {
  assert.equal(findHanLiterals("val c = '中'").length, 1);
});

test('ignores clean code', () => {
  assert.deepEqual(findHanLiterals('val a = "hello" // 中\nval b = \'x\''), []);
});
