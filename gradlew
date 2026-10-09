#!/usr/bin/env sh
# Lightweight Gradle launcher for CI environments where the Gradle wrapper JAR
# is not committed. Downloads the pinned Gradle distribution once and reuses it.
set -eu

GRADLE_VERSION="8.13"
GRADLE_HOME_BASE="${GRADLE_USER_HOME:-${HOME}/.gradle}/bootstrap"
GRADLE_HOME="${GRADLE_HOME_BASE}/gradle-${GRADLE_VERSION}"
GRADLE_ZIP="${GRADLE_HOME_BASE}/gradle-${GRADLE_VERSION}-bin.zip"

if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  mkdir -p "${GRADLE_HOME_BASE}"
  if [ ! -f "${GRADLE_ZIP}" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl --fail --location --retry 3 --output "${GRADLE_ZIP}" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "${GRADLE_ZIP}" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    else
      echo "Error: curl or wget is required to download Gradle." >&2
      exit 1
    fi
  fi
  command -v unzip >/dev/null 2>&1 || {
    echo "Error: unzip is required to install Gradle." >&2
    exit 1
  }
  unzip -q -o "${GRADLE_ZIP}" -d "${GRADLE_HOME_BASE}"
fi

exec "${GRADLE_HOME}/bin/gradle" "$@"
