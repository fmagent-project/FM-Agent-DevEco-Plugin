#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVECO_HOME="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
PLUGIN_ID="fm-agent-deveco-plugin"
PLUGIN_VERSION="0.4.0"

if [[ ! -d "$DEVECO_HOME" ]]; then
  echo "DevEco home not found: $DEVECO_HOME" >&2
  echo "Set DEVECO_HOME=/path/to/DevEco-Studio.app/Contents and retry." >&2
  exit 1
fi

JAVAC_BIN="${JAVAC:-}"
if [[ -z "$JAVAC_BIN" ]]; then
  for candidate in "$ROOT_DIR"/build/tools/*/Contents/Home/bin/javac; do
    if [[ -x "$candidate" ]]; then
      JAVAC_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$JAVAC_BIN" ]]; then
  JAVAC_BIN="$(command -v javac || true)"
fi
if [[ -z "$JAVAC_BIN" ]]; then
  echo "javac is required. Install a JDK 21+ or set JAVAC=/path/to/javac." >&2
  exit 1
fi

if ! "$JAVAC_BIN" --release 21 -version >/dev/null 2>&1; then
  echo "JDK 21+ is required because DevEco Studio 6.1 platform classes use Java 21 bytecode." >&2
  echo "Current javac: $JAVAC_BIN" >&2
  echo "Set JAVAC=/path/to/jdk-21/bin/javac and retry." >&2
  exit 1
fi

JAR_BIN="${JAR:-$(dirname "$JAVAC_BIN")/jar}"
if [[ ! -x "$JAR_BIN" ]]; then
  JAR_BIN="$(command -v jar || true)"
fi
if [[ -z "$JAR_BIN" ]]; then
  echo "jar is required. Install a JDK 21+ or set JAR=/path/to/jar." >&2
  exit 1
fi

if ! command -v zip >/dev/null 2>&1; then
  echo "zip is required and was not found on PATH." >&2
  exit 1
fi

BUILD_DIR="$ROOT_DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
PACKAGE_ROOT="$BUILD_DIR/package"
PLUGIN_DIR="$PACKAGE_ROOT/$PLUGIN_ID"
DIST_DIR="$BUILD_DIR/distributions"
ZIP_PATH="$DIST_DIR/$PLUGIN_ID-$PLUGIN_VERSION.zip"

rm -rf "$CLASSES_DIR" "$PACKAGE_ROOT" "$DIST_DIR"
mkdir -p "$CLASSES_DIR" "$PLUGIN_DIR/lib" "$DIST_DIR"

SOURCES_FILE="$BUILD_DIR/sources.list"
find "$ROOT_DIR/src/main/java" -name '*.java' | sort > "$SOURCES_FILE"
if [[ ! -s "$SOURCES_FILE" ]]; then
  echo "No Java sources found." >&2
  exit 1
fi

IDE_CLASSPATH="$DEVECO_HOME/lib/app.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/lib.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/util.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/util-8.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/util_rt.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/annotations.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/forms_rt.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/trove.jar"
IDE_CLASSPATH="$IDE_CLASSPATH:$DEVECO_HOME/lib/platform-loader.jar"

"$JAVAC_BIN" \
  -encoding UTF-8 \
  --release 21 \
  -cp "$IDE_CLASSPATH" \
  -d "$CLASSES_DIR" \
  @"$SOURCES_FILE"

"$JAR_BIN" cf "$PLUGIN_DIR/lib/$PLUGIN_ID.jar" \
  -C "$CLASSES_DIR" . \
  -C "$ROOT_DIR/src/main/resources" .

(
  cd "$PACKAGE_ROOT"
  zip -qr "$ZIP_PATH" "$PLUGIN_ID"
)

echo "Built plugin: $ZIP_PATH"
echo "Import it in DevEco Studio: Settings/Preferences > Plugins > gear icon > Install Plugin from Disk"
