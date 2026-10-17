#!/bin/sh
# Starts SeaweedFS (master + volume + filer + S3 gateway in one process) for
# local development. Writes the S3 identity file from environment variables so
# no credentials are committed. Two identities:
#   root  - administers buckets (used only by storage-init and verify.sh)
#   app   - wellness-api: read/write/list on the audio bucket only
# There is no anonymous identity, so unauthenticated requests are denied.
set -eu

umask 077
cat > /tmp/s3.json <<JSON
{
  "identities": [
    {
      "name": "root",
      "credentials": [{"accessKey": "${STORAGE_ROOT_ACCESS_KEY}", "secretKey": "${STORAGE_ROOT_SECRET_KEY}"}],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    },
    {
      "name": "wellness-api",
      "credentials": [{"accessKey": "${STORAGE_APP_ACCESS_KEY}", "secretKey": "${STORAGE_APP_SECRET_KEY}"}],
      "actions": ["Read:${AUDIO_BUCKET}", "Write:${AUDIO_BUCKET}", "List:${AUDIO_BUCKET}"]
    }
  ]
}
JSON

exec weed server -dir=/data -s3 -s3.config=/tmp/s3.json -s3.port=8333
