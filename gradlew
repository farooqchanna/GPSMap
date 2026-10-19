#!/usr/bin/env sh
# Lightweight Gradle launcher for environments where the standard wrapper JAR is not included.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.9
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-${GRADLE_VERSION}-all/local/gradle-${GRADLE_VERSION}"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$(dirname "$GRADLE_HOME")"
  TMP_ZIP="${TMPDIR:-/tmp}/gradle-${GRADLE_VERSION}-all.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-all.zip" -o "$TMP_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-all.zip" -O "$TMP_ZIP"
  else
    echo "Please install curl or wget to download Gradle ${GRADLE_VERSION}." >&2; exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "Please install unzip." >&2; exit 1; }
  unzip -q "$TMP_ZIP" -d "$(dirname "$GRADLE_HOME")"
  rm -f "$TMP_ZIP"
fi
exec "$GRADLE_HOME/bin/gradle" -p "$APP_HOME" "$@"
