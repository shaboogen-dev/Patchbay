#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
find "$HERE/src" -name '*.java' -print > "$BUILD/sources.txt"
javac --release 21 -d "$BUILD/classes" @"$BUILD/sources.txt"
printf 'Main-Class: dev.bitwigre.bml.BmlControlApp\n' > "$BUILD/manifest.mf"
jar --create --file "$HERE/bml-control.jar" --manifest "$BUILD/manifest.mf" -C "$BUILD/classes" .
