#!/usr/bin/env bash
# Inspect the pushed backend image (linux/amd64 required for App Runner).
# Assumes AWS CLI is configured and authenticated.
set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

AWS_REGION=us-east-1
ACCOUNT_ID=876067771140
REPOSITORY=todays-overview-backend
IMAGE_TAG=latest
IMAGE_URI="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$REPOSITORY:$IMAGE_TAG"

printf "Inspecting image: %s\n" "$IMAGE_URI"
docker buildx imagetools inspect "$IMAGE_URI"

printf "Confirm the image reports Platform: linux/amd64.\n"
