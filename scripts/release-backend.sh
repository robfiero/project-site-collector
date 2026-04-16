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
  print_error "Invalid environment: $ENVIRONMENT (expected dev or prod)"
  exit 1
fi

BUILD_SCRIPT="$REPO_ROOT/scripts/build/build.sh"
PUSH_SCRIPT="$REPO_ROOT/scripts/build/backend-build-push.sh"

if [[ ! -x "$BUILD_SCRIPT" ]]; then
  print_error "Backend build script not found or not executable: $BUILD_SCRIPT"
  exit 1
fi

if [[ "$ENVIRONMENT" == "dev" ]]; then
  print_banner "BACKEND RELEASE" \
    "Environment" "$ENVIRONMENT"

  "$BUILD_SCRIPT"
  print_success "Backend build/tests complete."
  exit 0
fi

# prod — load secrets from prod.local.env
ENV_FILE="$REPO_ROOT/scripts/env/prod.local.env"
if [[ ! -f "$ENV_FILE" ]]; then
  print_error "prod.local.env not found: $ENV_FILE"
  print_error "Copy scripts/env/prod.env.example to scripts/env/prod.local.env and fill in your values."
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

for var in ECR_REPOSITORY APP_RUNNER_SERVICE_ARN AWS_REGION; do
  if [[ -z "${!var:-}" ]]; then
    print_error "Required variable $var is not set in prod.local.env"
    exit 1
  fi
done

if [[ ! -x "$PUSH_SCRIPT" ]]; then
  print_error "Backend push script not found or not executable: $PUSH_SCRIPT"
  exit 1
fi

if ! command -v aws >/dev/null 2>&1; then
  print_error "AWS CLI not found. Install and configure aws before deploying."
  exit 1
fi

print_banner "BACKEND RELEASE" \
  "Environment"   "$ENVIRONMENT" \
  "Repository"    "$ECR_REPOSITORY" \
  "Service ARN"   "$APP_RUNNER_SERVICE_ARN" \
  "AWS Region"    "$AWS_REGION" \
  "Image Tag"     "${BACKEND_IMAGE_TAG:-latest}" \
  "AWS Profile"   "${AWS_PROFILE:-default}"

"$BUILD_SCRIPT"

PROFILE_ARGS=()
if [[ -n "${AWS_PROFILE:-}" ]]; then
  PROFILE_ARGS=(--profile "$AWS_PROFILE")
fi

"$PUSH_SCRIPT"
aws apprunner start-deployment \
  --service-arn "$APP_RUNNER_SERVICE_ARN" \
  --region "$AWS_REGION" \
  "${PROFILE_ARGS[@]+"${PROFILE_ARGS[@]}"}"

print_success "Backend release completed successfully"
