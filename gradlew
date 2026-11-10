#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.9"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/my-player-gradle"
GRADLE_HOME="$BASE/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    else echo "curl or wget is required." >&2; exit 1; fi
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required." >&2; exit 1; }
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$GRADLE_BIN" "$@"
