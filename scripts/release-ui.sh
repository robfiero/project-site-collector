#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib/banner.sh"

usage() {
  echo "Usage:" >&2
  echo "  $0 dev" >&2
  echo "  $0 prod <s3-bucket> <cloudfront-distribution-id> [aws-profile]" >&2
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

ENVIRONMENT="$1"
BUCKET="${2-}"
DIST_ID="${3-}"
PROFILE="${4-}"

if [[ "$ENVIRONMENT" != "dev" && "$ENVIRONMENT" != "prod" ]]; then
  echo "Invalid environment: $ENVIRONMENT (expected dev or prod)" >&2
  exit 1
fi

BUILD_SCRIPT="$REPO_ROOT/scripts/build/ui-build.sh"
DEPLOY_SCRIPT="$REPO_ROOT/scripts/deploy/ui-deploy-s3.sh"
INVALIDATE_SCRIPT="$REPO_ROOT/scripts/deploy/ui-cloudfront-invalidate.sh"

if [[ ! -x "$BUILD_SCRIPT" ]]; then
  print_error "UI build script not found or not executable: $BUILD_SCRIPT"
  exit 1
fi

if [[ "$ENVIRONMENT" == "dev" ]]; then
  if [[ $# -gt 1 ]]; then
    print_error "Dev release takes no additional arguments."
    usage
    exit 1
  fi

  ENV_FILE="$REPO_ROOT/scripts/env/${ENVIRONMENT}.env"
  unset VITE_API_BASE_URL
  set -a
  source "$ENV_FILE"
  set +a

  print_banner "UI RELEASE" \
    "Environment" "$ENVIRONMENT" \
    "API Base URL" "$VITE_API_BASE_URL"

  "$BUILD_SCRIPT" "$ENVIRONMENT"
  print_success "UI release completed successfully"
  exit 0
fi

if [[ $# -lt 3 || $# -gt 4 ]]; then
  usage
  exit 1
fi

if [[ ! -x "$DEPLOY_SCRIPT" ]]; then
  print_error "UI deploy script not found or not executable: $DEPLOY_SCRIPT"
  exit 1
fi

if [[ ! -x "$INVALIDATE_SCRIPT" ]]; then
  print_error "CloudFront invalidate script not found or not executable: $INVALIDATE_SCRIPT"
  exit 1
fi

ENV_FILE="$REPO_ROOT/scripts/env/${ENVIRONMENT}.env"
unset VITE_API_BASE_URL
set -a
source "$ENV_FILE"
set +a

print_banner "UI RELEASE" \
  "Environment" "$ENVIRONMENT" \
  "API Base URL" "$VITE_API_BASE_URL" \
  "S3 Bucket" "$BUCKET" \
  "CloudFront Dist" "$DIST_ID" \
  "AWS Profile" "${PROFILE:-default}"

if [[ -n "$PROFILE" ]]; then
  "$BUILD_SCRIPT" "$ENVIRONMENT"
  "$DEPLOY_SCRIPT" "$BUCKET" "$PROFILE"
  "$INVALIDATE_SCRIPT" "$DIST_ID" "/*" "$PROFILE"
else
  "$BUILD_SCRIPT" "$ENVIRONMENT"
  "$DEPLOY_SCRIPT" "$BUCKET"
  "$INVALIDATE_SCRIPT" "$DIST_ID" "/*"
fi

print_success "UI release completed successfully"
