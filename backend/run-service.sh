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

# Apple Silicon + Homebrew Argon2 native library support for Jargon2 RI backend.
if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" && -f "/opt/homebrew/lib/libargon2.dylib" ]]; then
  if [[ -n "${JAVA_TOOL_OPTIONS:-}" ]]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djava.library.path=/opt/homebrew/lib"
  else
    export JAVA_TOOL_OPTIONS="-Djava.library.path=/opt/homebrew/lib"
  fi
  echo "Detected Apple Silicon + Homebrew Argon2; enabling native RI backend."
fi

exec mvn -pl service -am -DskipTests package exec:java "$@"
