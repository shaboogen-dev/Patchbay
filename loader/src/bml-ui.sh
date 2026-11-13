#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ROOT/loader/platform.sh"
bml_unquarantine "$ROOT"
APP="$ROOT/loader/app/bml-control.jar"
if [ ! -f "$APP" ]; then
  echo "ERROR: BML control application not found: $APP" >&2
  exit 2
fi
# The window runs on Bitwig's own Java (21 modules, Swing among them) unless BML_JAVA says otherwise;
# see bml_find_java in loader/platform.sh for the order.
JAVA=$(bml_find_java "$ROOT" || true)
if [ -z "$JAVA" ]; then
  echo "ERROR: No Java to run Patchbay was found. Install Bitwig Studio (Patchbay runs on its Java), or set BML_JAVA or JAVA_HOME to a Java 21 or later." >&2
  exit 3
fi
if [ "$BML_OS" = Darwin ]; then
  exec "$JAVA" -Xdock:name=Patchbay --enable-native-access=ALL-UNNAMED -Dbml.root="$ROOT" -jar "$APP"
fi
exec "$JAVA" --enable-native-access=ALL-UNNAMED -Dbml.root="$ROOT" -jar "$APP"
