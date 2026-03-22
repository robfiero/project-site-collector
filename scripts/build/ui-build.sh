#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/banner.sh"

usage() {
  echo "Usage: $0 <dev|prod>" >&2
  echo "Example: $0 dev" >&2
  echo "Example: $0 prod" >&2
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

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UI_DIR="$REPO_ROOT/ui"
ENV_FILE="$REPO_ROOT/scripts/env/${ENVIRONMENT}.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

if [[ ! -d "$UI_DIR" ]]; then
  echo "UI directory not found: $UI_DIR" >&2
  exit 1
fi

if [[ ! -f "$UI_DIR/package.json" ]]; then
  echo "package.json not found in $UI_DIR" >&2
  exit 1
fi

unset VITE_API_BASE_URL
set -a
source "$ENV_FILE"
set +a

if [[ -z "${VITE_API_BASE_URL:-}" ]]; then
  echo "VITE_API_BASE_URL is not set after sourcing $ENV_FILE" >&2
  exit 1
fi

print_banner "UI BUILD" \
  "Environment" "$ENVIRONMENT" \
  "API Base URL" "$VITE_API_BASE_URL"

cd "$UI_DIR"

if [[ -f package-lock.json ]]; then
  echo "Installing dependencies with npm ci..."
  npm ci
else
  echo "Installing dependencies with npm install..."
  npm install
fi

echo "Building UI..."
npm run build

echo "Running UI tests..."
npm test

if [[ ! -d "$UI_DIR/dist" ]]; then
  echo "Build completed but dist/ was not found at $UI_DIR/dist" >&2
  exit 1
fi

if command -v rg >/dev/null 2>&1; then
  SEARCH_CMD=(rg -q)
else
  SEARCH_CMD=(grep -R -q)
fi

if [[ "$ENVIRONMENT" == "prod" ]]; then
  if "${SEARCH_CMD[@]}" "localhost" "$UI_DIR/dist"; then
    echo "Production build contains localhost references. Aborting." >&2
    exit 1
  fi
  if ! "${SEARCH_CMD[@]}" "https://api.todaysoverview.robfiero.net" "$UI_DIR/dist"; then
    echo "Production build does not contain expected API base URL." >&2
    exit 1
  fi
else
  if ! "${SEARCH_CMD[@]}" "http://localhost:8080" "$UI_DIR/dist"; then
    echo "Dev build does not contain expected API base URL." >&2
    exit 1
  fi
fi

print_success "UI build complete: $UI_DIR/dist"
