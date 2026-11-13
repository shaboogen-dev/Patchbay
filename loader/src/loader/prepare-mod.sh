#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$HERE/platform.sh"
# The Java the window runs on (it passes it as BML_JAVA), else the order in bml_find_java: the runtime
# of the Bitwig being prepared for ($1, its bin folder; Contents/Java on macOS) first.
JAVA=$(bml_find_java "$(dirname -- "$HERE")" "${1:-}" || true)
if [ -z "${JAVA:-}" ]; then echo 'Java 21+ not found: install Bitwig Studio, or set BML_JAVA or JAVA_HOME' >&2; exit 3; fi
if [ "$#" -ge 2 ]; then
  exec "$JAVA" -cp "$HERE/app/bml-control.jar" dev.bitwigre.bml.JavaPreparer --loader-root "$HERE" --bitwig-bin "$1" --mods-root "$2"
elif [ "$#" -eq 1 ]; then
  exec "$JAVA" -cp "$HERE/app/bml-control.jar" dev.bitwigre.bml.JavaPreparer --loader-root "$HERE" --bitwig-bin "$1"
else
  exec "$JAVA" -cp "$HERE/app/bml-control.jar" dev.bitwigre.bml.JavaPreparer --loader-root "$HERE"
fi
