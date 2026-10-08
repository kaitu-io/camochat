// 陈仓媒体中转 · 上传签名。
// 服务器只签发「往 b/<blob_id> 放一个指定长度的新对象」的 5 分钟许可；
// 它看不到任何密钥，也不记录任何请求。全文公开在各分发的 /source 页。
import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';

export const LIMITS = { 1: 2097152, 2: 2097152, 3: 31457280 }; // 语音 / 图片 / 视频（.cca 总字节）
export const MIN_BYTE_LEN = 50; // 最小合法 .cca：34 字节头 + 16 字节 AEAD tag（= core payload::MEDIA_MIN_BLOB_LEN）
const BLOB_ID = /^[A-Za-z0-9_-]{22}$/;
const POSITIVE_INT = /^[1-9][0-9]{0,9}$/;

export function validate(q = {}) {
  if (typeof q.blob_id !== 'string' || !BLOB_ID.test(q.blob_id)) return { ok: false, status: 400, error: 'bad blob_id' };
  if (!['1', '2', '3'].includes(q.kind)) return { ok: false, status: 400, error: 'bad kind' };
  if (typeof q.byte_len !== 'string' || !POSITIVE_INT.test(q.byte_len)) return { ok: false, status: 400, error: 'bad byte_len' };
  const kind = Number(q.kind);
  const byteLen = Number(q.byte_len);
  if (byteLen < MIN_BYTE_LEN) return { ok: false, status: 400, error: 'too small' };
  if (byteLen > LIMITS[kind]) return { ok: false, status: 413, error: 'too large' };
  return { ok: true, blobId: q.blob_id, byteLen, kind };
}

export async function presign({ bucket, region, blobId, byteLen, credentials }) {
  const client = new S3Client({ region, ...(credentials ? { credentials } : {}) });
  const cmd = new PutObjectCommand({
    Bucket: bucket,
    Key: `b/${blobId}`,
    ContentLength: byteLen,
    ContentType: 'application/octet-stream',
    IfNoneMatch: '*',
  });
  return getSignedUrl(client, cmd, {
    expiresIn: 300,
    signableHeaders: new Set(['content-length', 'content-type', 'if-none-match']),
    unhoistableHeaders: new Set(['if-none-match']),
  });
}
