#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Remove Maven build outputs for all backend modules.
cd "$REPO_ROOT/backend"
mvn clean

# Remove common frontend compiled output, if present.
rm -rf "$REPO_ROOT/ui/dist"
