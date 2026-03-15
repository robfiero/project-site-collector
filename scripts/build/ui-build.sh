#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UI_DIR="$REPO_ROOT/ui"

if [[ ! -d "$UI_DIR" ]]; then
  echo "UI directory not found: $UI_DIR" >&2
  exit 1
fi

if [[ ! -f "$UI_DIR/package.json" ]]; then
  echo "package.json not found in $UI_DIR" >&2
  exit 1
fi

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

if [[ ! -d "$UI_DIR/dist" ]]; then
  echo "Build completed but dist/ was not found at $UI_DIR/dist" >&2
  exit 1
fi

echo "UI build complete: $UI_DIR/dist"
