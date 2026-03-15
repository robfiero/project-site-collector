#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT" >/dev/null

usage() {
  echo "Usage: $0 <distribution-id> [paths] [aws-profile]" >&2
  echo "Example: $0 E123ABC456DEF" >&2
  echo "Example: $0 E123ABC456DEF \"/*\"" >&2
  echo "Example: $0 E123ABC456DEF \"/*\" my-aws-profile" >&2
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

if ! command -v aws >/dev/null 2>&1; then
  echo "AWS CLI not found. Install and configure aws before invalidating." >&2
  exit 1
fi

DIST_ID="$1"
PATHS="${2-/*}"
PROFILE="${3-}"

PROFILE_ARGS=()
if [[ -n "$PROFILE" ]]; then
  PROFILE_ARGS+=(--profile "$PROFILE")
fi

echo "Creating CloudFront invalidation for distribution $DIST_ID (paths: $PATHS)..."
aws cloudfront create-invalidation --distribution-id "$DIST_ID" --paths "$PATHS" "${PROFILE_ARGS[@]}"

echo "Invalidation request submitted."
