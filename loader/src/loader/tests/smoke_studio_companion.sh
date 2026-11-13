#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/.." && pwd)
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
javac --release 21 -cp "$ROOT/app/bml-control.jar" -d "$BUILD" "$HERE/StudioCompanionInventorySmoke.java"
java -cp "$BUILD:$ROOT/app/bml-control.jar" dev.bitwigre.bml.StudioCompanionInventorySmoke
