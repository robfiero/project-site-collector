#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Remove Maven build outputs for all backend modules.
cd "$REPO_ROOT/backend"
mvn clean

# Remove frontend build/test caches and generated output.
rm -rf \
  "$REPO_ROOT/ui/dist" \
  "$REPO_ROOT/ui/.vitest" \
  "$REPO_ROOT/ui/.cache" \
  "$REPO_ROOT/ui/coverage" \
  "$REPO_ROOT/ui/node_modules/.vite"

echo "Clean complete: backend targets + UI build/test caches removed."
