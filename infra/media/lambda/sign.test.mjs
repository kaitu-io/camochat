import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validate, LIMITS, MIN_BYTE_LEN, presign } from './sign.mjs';

const ID = 'AAAAAAAAAAAAAAAAAAAAAA'; // 22 chars

test('accepts a well-formed request', () => {
  assert.deepEqual(validate({ blob_id: ID, byte_len: '1000', kind: '2' }),
    { ok: true, blobId: ID, byteLen: 1000, kind: 2 });
});

test('rejects bad blob ids', () => {
  for (const blob_id of [undefined, '', 'A'.repeat(21), 'A'.repeat(23), '../AAAAAAAAAAAAAAAAAAA', 'AAAAAAAAAA/AAAAAAAAAAA', 'AAAAAAAAAAAAAAAAAAAAA=']) {
    assert.equal(validate({ blob_id, byte_len: '1000', kind: '1' }).status, 400, String(blob_id));
  }
});

test('rejects bad kinds', () => {
  for (const kind of [undefined, '0', '4', '1.0', 'x']) {
    assert.equal(validate({ blob_id: ID, byte_len: '1000', kind }).status, 400, String(kind));
  }
});

test('rejects bad lengths', () => {
  for (const byte_len of [undefined, '', '-1', '0', '1.5', '1e3', ' 10', '00x']) {
    assert.equal(validate({ blob_id: ID, byte_len, kind: '1' }).status, 400, String(byte_len));
  }
});

test('enforces the 50-byte minimum (smallest valid .cca)', () => {
  assert.equal(MIN_BYTE_LEN, 50);
  for (const byte_len of ['1', '10', '49']) {
    const r = validate({ blob_id: ID, byte_len, kind: '1' });
    assert.equal(r.status, 400, byte_len);
    assert.equal(r.ok, false, byte_len);
  }
  for (const kind of ['1', '2', '3']) {
    assert.deepEqual(validate({ blob_id: ID, byte_len: '50', kind }),
      { ok: true, blobId: ID, byteLen: 50, kind: Number(kind) });
  }
});

test('enforces per-kind size limits', () => {
  assert.equal(validate({ blob_id: ID, byte_len: String(LIMITS[1]), kind: '1' }).ok, true);
  assert.equal(validate({ blob_id: ID, byte_len: String(LIMITS[1] + 1), kind: '1' }).status, 413);
  assert.equal(validate({ blob_id: ID, byte_len: String(LIMITS[2] + 1), kind: '2' }).status, 413);
  assert.equal(validate({ blob_id: ID, byte_len: String(LIMITS[3]), kind: '3' }).ok, true);
  assert.equal(validate({ blob_id: ID, byte_len: String(LIMITS[3] + 1), kind: '3' }).status, 413);
});

test('presigned url binds key, length, no-overwrite and 300s expiry', async () => {
  const url = new URL(await presign({ bucket: 'chencang-media', region: 'ap-northeast-1', blobId: ID, byteLen: 1234,
    credentials: { accessKeyId: 'AKIDEXAMPLE', secretAccessKey: 'secret' } }));
  assert.equal(url.hostname, 'chencang-media.s3.ap-northeast-1.amazonaws.com');
  assert.equal(url.pathname, `/b/${ID}`);
  assert.equal(url.searchParams.get('X-Amz-Expires'), '300');
  const signed = url.searchParams.get('X-Amz-SignedHeaders').split(';');
  assert.ok(signed.includes('content-length'), signed.join(';'));
  assert.ok(signed.includes('content-type'), signed.join(';'));
  assert.ok(signed.includes('if-none-match'), signed.join(';'));
});
