// 陈仓媒体中转 · Lambda 入口（Function URL，经 CloudFront /api/upload 访问）。
import { validate, presign } from './sign.mjs';

const json = (statusCode, body) => ({
  statusCode,
  headers: {
    'content-type': 'application/json',
    'cache-control': 'no-store',
    'x-cc-source-sha256': process.env.SOURCE_SHA256 ?? 'unknown',
  },
  body: JSON.stringify(body),
});

export async function handler(event) {
  if (event?.requestContext?.http?.method !== 'GET') return json(405, { error: 'GET only' });
  const v = validate(event.queryStringParameters ?? {});
  if (!v.ok) return json(v.status, { error: v.error });
  const url = await presign({ bucket: process.env.BUCKET, region: process.env.AWS_REGION, blobId: v.blobId, byteLen: v.byteLen });
  return json(200, { url, expires_in: 300 });
}
