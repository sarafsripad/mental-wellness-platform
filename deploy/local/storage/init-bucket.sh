#!/bin/sh
# One-shot, idempotent bucket setup for local development:
#  - private audio bucket (no anonymous identity exists on the server)
#  - default server-side encryption (SSE-S3, AES256)
#  - lifecycle rule expiring objects after AUDIO_EXPIRY_DAYS. This is the local
#    default for the proposed processing window; the real period is pending
#    privacy/legal review (docs/privacy/consent-and-data-lifecycle.md).
set -eu

s3() { aws --endpoint-url "$S3_ENDPOINT" "$@"; }

if ! s3 s3api head-bucket --bucket "$AUDIO_BUCKET" >/dev/null 2>&1; then
  s3 s3api create-bucket --bucket "$AUDIO_BUCKET" >/dev/null
fi

s3 s3api put-bucket-encryption --bucket "$AUDIO_BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

s3 s3api put-bucket-lifecycle-configuration --bucket "$AUDIO_BUCKET" \
  --lifecycle-configuration "{\"Rules\":[{\"ID\":\"expire-processing-window\",\"Status\":\"Enabled\",\"Filter\":{\"Prefix\":\"\"},\"Expiration\":{\"Days\":${AUDIO_EXPIRY_DAYS}}}]}"

echo "storage-init: bucket '$AUDIO_BUCKET' ready (SSE-S3 default, expiry ${AUDIO_EXPIRY_DAYS}d)"
