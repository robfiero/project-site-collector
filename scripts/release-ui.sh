#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib/banner.sh"

usage() {
  echo "Usage:" >&2
  echo "  $0 dev" >&2
  echo "  $0 prod" >&2
  echo "" >&2
  echo "  prod requires scripts/env/prod.local.env (copy from scripts/env/prod.env.example)" >&2
}

if [[ $# -ne 1 ]]; then
  usage
  exit 1
fi

ENVIRONMENT="$1"
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

# prod — load secrets from prod.local.env
ENV_FILE="$REPO_ROOT/scripts/env/prod.local.env"
if [[ ! -f "$ENV_FILE" ]]; then
  print_error "prod.local.env not found: $ENV_FILE"
  print_error "Copy scripts/env/prod.env.example to scripts/env/prod.local.env and fill in your values."
  exit 1
fi

unset VITE_API_BASE_URL
set -a
source "$REPO_ROOT/scripts/env/prod.env"
source "$ENV_FILE"
set +a

for var in S3_BUCKET_UI CLOUDFRONT_DISTRIBUTION_ID; do
  if [[ -z "${!var:-}" ]]; then
    print_error "Required variable $var is not set in prod.local.env"
    exit 1
  fi
done

if [[ ! -x "$DEPLOY_SCRIPT" ]]; then
  print_error "UI deploy script not found or not executable: $DEPLOY_SCRIPT"
  exit 1
fi

if [[ ! -x "$INVALIDATE_SCRIPT" ]]; then
  print_error "CloudFront invalidate script not found or not executable: $INVALIDATE_SCRIPT"
  exit 1
fi

print_banner "UI RELEASE" \
  "Environment"   "$ENVIRONMENT" \
  "API Base URL"  "${VITE_API_BASE_URL:-}" \
  "S3 Bucket"     "$S3_BUCKET_UI" \
  "CloudFront"    "$CLOUDFRONT_DISTRIBUTION_ID" \
  "AWS Profile"   "${AWS_PROFILE:-default}"

PROFILE="${AWS_PROFILE:-}"

"$BUILD_SCRIPT" "$ENVIRONMENT"
"$DEPLOY_SCRIPT" "$S3_BUCKET_UI" "$PROFILE"
"$INVALIDATE_SCRIPT" "$CLOUDFRONT_DISTRIBUTION_ID" "/*" "$PROFILE"

print_success "UI release completed successfully"
