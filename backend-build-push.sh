#!/usr/bin/env bash
# Build and push the backend image to ECR (linux/amd64 required for App Runner).
# Assumes AWS CLI is configured and authenticated.
set -e

AWS_REGION=us-east-1
ACCOUNT_ID=876067771140
REPOSITORY=todays-overview-backend
IMAGE_TAG=latest
IMAGE_URI="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$REPOSITORY:$IMAGE_TAG"

printf "Verifying AWS CLI identity...\n"
aws sts get-caller-identity

printf "Logging in to ECR...\n"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

printf "Building and pushing image (%s)...\n" "$IMAGE_URI"
docker buildx build \
  --platform linux/amd64 \
  -f Dockerfile.backend \
  -t "$IMAGE_URI" \
  --push .

printf "Push complete: %s\n" "$IMAGE_URI"
