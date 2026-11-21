#!/usr/bin/env bash
# Small wrapper bootstrapper. Uses the exact Gradle version configured for this project.
set -euo pipefail
VERSION="9.6.0"
EXPECTED_SHA256="bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01"
if command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -q "Gradle ${VERSION}"; then
  exec gradle "$@"
fi

CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/noctis-wrapper"
DIST_DIR="${CACHE_ROOT}/gradle-${VERSION}"
GRADLE_BIN="${DIST_DIR}/bin/gradle"
if [[ ! -x "$GRADLE_BIN" ]]; then
  mkdir -p "$CACHE_ROOT"
  ZIP_FILE="${CACHE_ROOT}/gradle-${VERSION}-bin.zip"
  echo "Downloading Gradle ${VERSION} from services.gradle.org..."
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip" --output "$ZIP_FILE"
  else
    echo "curl is required to bootstrap Gradle ${VERSION}. Use the Nix development shell." >&2
    exit 127
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "unzip is required to bootstrap Gradle ${VERSION}. Use the Nix development shell." >&2
    exit 127
  fi
  ACTUAL_SHA256="$(sha256sum "$ZIP_FILE" | cut -d ' ' -f 1)"
  if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    rm -f "$ZIP_FILE"
    echo "Gradle distribution checksum mismatch; refusing to execute it." >&2
    exit 1
  fi
  unzip -q -o "$ZIP_FILE" -d "$CACHE_ROOT"
  rm -f "$ZIP_FILE"
fi
exec "$GRADLE_BIN" "$@"
