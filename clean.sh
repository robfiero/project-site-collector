#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEEP_CLEAN=false

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [--deep]" >&2
  exit 1
fi

if [[ $# -eq 1 ]]; then
  case "$1" in
    --deep)
      DEEP_CLEAN=true
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--deep]" >&2
      exit 1
      ;;
  esac
fi

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

if [[ "$DEEP_CLEAN" == "true" ]]; then
  rm -rf "$REPO_ROOT/ui/node_modules"
  echo "Deep clean complete: backend targets + UI caches + ui/node_modules removed."
else
  echo "Clean complete: backend targets + UI build/test caches removed."
fi
