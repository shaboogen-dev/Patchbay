#!/bin/sh
# Builds the BML agent for macOS from the source beside this script: bitwig-jvmti-tracer.dylib,
# one universal file (arm64 + x86_64), with the Xcode command-line tools' clang, signed ad hoc.
# It runs only on a Mac.
#   sh build-agent-mac.sh [output file, default out/bitwig-jvmti-tracer.dylib beside this script]
#
# Inputs, beside this script: src/bitwig_jvmti_tracer.cpp, include/darwin/jni_md.h, and the pinned
# JDK's jni.h and jvmti.h (the same on every platform; not in BML's repository, standard 12) in
# jdk-include/ or in the folder BML_JDK_INCLUDE names. `python loader/build.py --mac-kit DIR`
# packs all of them into a kit for the Mac.
#
# bitwig-jvmti-tracer.dylib.sha256, beside this script, records the pinned toolchain (standard 11),
# the inputs' SHA-256 and the dylib's: the build checks them and prints the dylib's SHA-256 and
# whether it is the recorded one. loader/build.py takes into a release only a dylib with that
# SHA-256. Flags follow the Linux build in loader/build.py: no debug information, only the three
# agent entry points exported, build paths mapped away, a fixed install name and signing
# identifier, so the same source and toolchain give the same file from whichever folder it is built.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
SRC="$HERE/src/bitwig_jvmti_tracer.cpp"
MD="$HERE/include/darwin"
JDKINC=$(CDPATH= cd -- "${BML_JDK_INCLUDE:-$HERE/jdk-include}" 2>/dev/null && pwd -P) || JDKINC=${BML_JDK_INCLUDE:-$HERE/jdk-include}
RECORD="$HERE/bitwig-jvmti-tracer.dylib.sha256"
OUT=${1:-$HERE/out/bitwig-jvmti-tracer.dylib}

if [ "$(uname -s)" != Darwin ]; then
  echo "ERROR: this builds the macOS agent; run it on a Mac."
  exit 2
fi
if ! xcode-select -p >/dev/null 2>&1; then
  echo "ERROR: the Xcode command-line tools are not installed. Run: xcode-select --install"
  exit 2
fi
for f in "$SRC" "$MD/jni_md.h" "$JDKINC/jni.h" "$JDKINC/jvmti.h" "$RECORD"; do
  if [ ! -f "$f" ]; then echo "ERROR: missing $f"; exit 3; fi
done

recorded() { sed -n "s|^$1=||p" "$RECORD" | head -n 1; }
sha() { shasum -a 256 "$1" | cut -d ' ' -f 1; }

# The toolchain, against the pinned one.
CLT=$(pkgutil --pkg-info=com.apple.pkg.CLTools_Executables 2>/dev/null | sed -n 's/^version: //p')
CLANG=$(xcrun clang --version 2>/dev/null | head -n 1)
SDK=$(xcrun --show-sdk-version 2>/dev/null)
echo "Toolchain: Command Line Tools ${CLT:-?}; ${CLANG:-?}; SDK ${SDK:-?}"
SAME_TOOLS=yes
if [ "$CLT" != "$(recorded clt)" ] || [ "$CLANG" != "$(recorded clang)" ] || [ "$SDK" != "$(recorded sdk)" ]; then
  SAME_TOOLS=no
  echo "NOTE: the pinned toolchain is Command Line Tools $(recorded clt); $(recorded clang); SDK $(recorded sdk)."
  echo "      With other tools the dylib's SHA-256 will most likely differ from the recorded one."
fi

# The inputs, against the ones the recorded dylib was built from.
SAME_INPUTS=yes
check_input() {
  if [ "$(sha "$2")" != "$(recorded "$1")" ]; then
    SAME_INPUTS=no
    echo "NOTE: $1 differs from the recorded input (sha256 $(sha "$2"))."
  fi
}
check_input src/bitwig_jvmti_tracer.cpp "$SRC"
check_input include/darwin/jni_md.h "$MD/jni_md.h"
check_input build-agent-mac.sh "$HERE/build-agent-mac.sh"
check_input jdk-include/jni.h "$JDKINC/jni.h"
check_input jdk-include/jvmti.h "$JDKINC/jvmti.h"

mkdir -p "$(dirname -- "$OUT")"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/bml-agent-build.XXXXXX")
trap 'rm -rf "$WORK"' EXIT INT TERM
printf '_Agent_OnLoad\n_Agent_OnAttach\n_Agent_OnUnload\n' >"$WORK/exports.txt"

# Bitwig 6.1.4's own executable targets macOS 12.0; the agent targets the same.
ZERO_AR_DATE=1 xcrun clang++ -arch arm64 -arch x86_64 -mmacosx-version-min=12.0 \
  -std=c++17 -O2 -shared -fPIC -fvisibility=hidden \
  -ffile-prefix-map="$HERE/src/=" -ffile-prefix-map="$JDKINC/=jdk/include/" -ffile-prefix-map="$HERE/include/=jdk/include/" \
  -I"$JDKINC" -I"$MD" \
  -Wl,-exported_symbols_list,"$WORK/exports.txt" -Wl,-x -Wl,-S \
  -Wl,-install_name,@rpath/bitwig-jvmti-tracer.dylib \
  -o "$WORK/bitwig-jvmti-tracer.dylib" "$SRC"

# Ad-hoc signature (no certificate): every arm64 binary must carry one. Bitwig's process
# disables library validation, so it may load a library that isn't signed by Bitwig.
codesign -s - -f -i dev.bml.bitwig-jvmti-tracer "$WORK/bitwig-jvmti-tracer.dylib"
mv -f "$WORK/bitwig-jvmti-tracer.dylib" "$OUT"
GOT=$(sha "$OUT")
echo "BUILT $OUT"
echo "Architectures: $(lipo -archs "$OUT" 2>/dev/null)"
if [ "$GOT" = "$(recorded dylib)" ]; then MATCH=yes; else MATCH=no; fi
echo
echo "BML_MAC_AGENT sha256=$GOT expected=$(recorded dylib) match=$MATCH same_toolchain=$SAME_TOOLS same_inputs=$SAME_INPUTS"
