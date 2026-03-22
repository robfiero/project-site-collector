#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UI_DIST="$REPO_ROOT/ui/dist"

usage() {
  echo "Usage: $0 <s3-bucket> [aws-profile]" >&2
  echo "Example: $0 my-ui-bucket" >&2
  echo "Example: $0 my-ui-bucket my-aws-profile" >&2
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

if ! command -v aws >/dev/null 2>&1; then
  echo "AWS CLI not found. Install and configure aws before deploying." >&2
  exit 1
fi

if [[ ! -d "$UI_DIST" ]]; then
  echo "UI dist directory not found: $UI_DIST" >&2
  echo "Run ./ui-build.sh first." >&2
  exit 1
fi

BUCKET="$1"
PROFILE="${2-}"

echo "Syncing $UI_DIST to s3://$BUCKET ..."
if [[ -n "$PROFILE" ]]; then
  aws s3 sync "$UI_DIST/" "s3://$BUCKET" --delete --profile "$PROFILE"
else
  aws s3 sync "$UI_DIST/" "s3://$BUCKET" --delete
fi

echo "S3 deploy complete: s3://$BUCKET"
