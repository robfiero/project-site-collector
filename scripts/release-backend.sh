#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

source "$SCRIPT_DIR/lib/banner.sh"

usage() {
  echo "Usage:" >&2
  echo "  $0 dev" >&2
  echo "  $0 prod <repository> <app-runner-service-arn> [aws-profile]" >&2
}

if [[ $# -lt 1 ]]; then
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

if [[ $# -lt 3 || $# -gt 4 ]]; then
  usage
  exit 1
fi

REPOSITORY="$2"
SERVICE_ARN="$3"
PROFILE="${4-}"

if [[ ! -x "$PUSH_SCRIPT" ]]; then
  print_error "Backend push script not found or not executable: $PUSH_SCRIPT"
  exit 1
fi

if ! command -v aws >/dev/null 2>&1; then
  print_error "AWS CLI not found. Install and configure aws before deploying."
  exit 1
fi

print_banner "BACKEND RELEASE" \
  "Environment" "$ENVIRONMENT" \
  "Repository" "$REPOSITORY" \
  "Service ARN" "$SERVICE_ARN" \
  "AWS Profile" "${PROFILE:-default}"

"$BUILD_SCRIPT"

if [[ -n "$PROFILE" ]]; then
  "$PUSH_SCRIPT" "$REPOSITORY" "$PROFILE"
  aws apprunner start-deployment --service-arn "$SERVICE_ARN" --region us-east-1 --profile "$PROFILE"
else
  "$PUSH_SCRIPT" "$REPOSITORY"
  aws apprunner start-deployment --service-arn "$SERVICE_ARN" --region us-east-1
fi

print_success "Backend release completed successfully"
