#!/usr/bin/env bash
# Build and push the backend image to ECR (linux/amd64 required for App Runner).
# Assumes AWS CLI is configured and authenticated.
set -euo pipefail

usage() {
  echo "Usage: $0 <repository> [aws-profile]" >&2
  echo "Example: $0 todays-overview-backend" >&2
  echo "Example: $0 todays-overview-backend my-aws-profile" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 1
fi

if ! command -v aws >/dev/null 2>&1; then
  echo "AWS CLI not found. Install and configure aws before pushing." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker not found. Install Docker before pushing." >&2
  exit 1
fi

REPOSITORY="$1"
PROFILE="${2-}"
AWS_REGION=us-east-1
IMAGE_TAG=latest

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

printf "Verifying AWS CLI identity...\n"
if [[ -n "$PROFILE" ]]; then
  ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text --profile "$PROFILE")"
else
  ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
fi

IMAGE_URI="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$REPOSITORY:$IMAGE_TAG"

printf "Logging in to ECR...\n"
if [[ -n "$PROFILE" ]]; then
  aws ecr get-login-password --region "$AWS_REGION" --profile "$PROFILE" \
    | docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
else
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
fi

printf "Building and pushing image (%s)...\n" "$IMAGE_URI"
docker buildx build \
  --platform linux/amd64 \
  -f Dockerfile.backend \
  -t "$IMAGE_URI" \
  --push .

printf "Push complete: %s\n" "$IMAGE_URI"
