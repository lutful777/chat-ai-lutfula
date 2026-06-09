#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="9.3.1"
GRADLE_DIR=".gradle-bin/gradle-${GRADLE_VERSION}"
GRADLE_ZIP=".gradle-bin/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

if [ ! -x "${GRADLE_DIR}/bin/gradle" ]; then
  mkdir -p .gradle-bin
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  curl -L --fail --retry 3 -o "${GRADLE_ZIP}" "${GRADLE_URL}"
  unzip -q "${GRADLE_ZIP}" -d .gradle-bin
fi

exec "${GRADLE_DIR}/bin/gradle" "$@"
