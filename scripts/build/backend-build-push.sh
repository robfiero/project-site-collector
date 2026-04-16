#!/usr/bin/env bash
# Build and push the backend image to ECR.
# Reads configuration from environment variables (set via prod.local.env or exported by the caller).
# All variables have sensible defaults and can be overridden in the environment.
set -euo pipefail

if ! command -v aws >/dev/null 2>&1; then
  echo "AWS CLI not found. Install and configure aws before pushing." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker not found. Install Docker before pushing." >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

REPOSITORY="${ECR_REPOSITORY:-}"
if [[ -z "$REPOSITORY" ]]; then
  echo "ECR_REPOSITORY is not set. Set it in prod.local.env or export it before running." >&2
  exit 1
fi

AWS_REGION="${AWS_REGION:-us-east-1}"
IMAGE_TAG="${BACKEND_IMAGE_TAG:-latest}"
DOCKER_PLATFORM="${DOCKER_PLATFORM:-linux/amd64}"

PROFILE_ARGS=()
if [[ -n "${AWS_PROFILE:-}" ]]; then
  PROFILE_ARGS=(--profile "$AWS_PROFILE")
fi

if [[ -n "${AWS_ACCOUNT_ID:-}" ]]; then
  ACCOUNT_ID="$AWS_ACCOUNT_ID"
else
  printf "Verifying AWS CLI identity...\n"
  ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text "${PROFILE_ARGS[@]+"${PROFILE_ARGS[@]}"}")"
fi

IMAGE_URI="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$REPOSITORY:$IMAGE_TAG"

printf "Logging in to ECR...\n"
aws ecr get-login-password --region "$AWS_REGION" "${PROFILE_ARGS[@]+"${PROFILE_ARGS[@]}"}" \
  | docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

printf "Building and pushing image (%s)...\n" "$IMAGE_URI"
docker buildx build \
  --platform "$DOCKER_PLATFORM" \
  -f Dockerfile.backend \
  -t "$IMAGE_URI" \
  --push .

printf "Push complete: %s\n" "$IMAGE_URI"
