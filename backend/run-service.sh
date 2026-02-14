#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# exec:java runs inside the Maven JVM; ensure preview is enabled there.
if [[ -n "${MAVEN_OPTS:-}" ]]; then
  export MAVEN_OPTS="--enable-preview ${MAVEN_OPTS}"
else
  export MAVEN_OPTS="--enable-preview"
fi

exec mvn -pl service -am -DskipTests package exec:java "$@"
