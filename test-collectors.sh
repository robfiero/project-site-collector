#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT/backend"

# Run collectors tests and build required dependencies.
mvn -pl collectors -am test
