# What differs between Linux and macOS for BML's sh scripts; they read it with
#   . "<BML>/loader/platform.sh"
# BML_OS      uname -s: Linux or Darwin (macOS)
# BML_NATIVE  the prefix that runs a program natively: "arch -arm64" on Apple silicon (also from a
#             Terminal that runs under Rosetta), else empty
# bml_bitwig_exe [PATH]  Bitwig's executable: PATH (on macOS the app bundle may be given), else the
#                        default, /opt/bitwig-studio/bitwig-studio or the one in /Applications/Bitwig Studio.app
# bml_bitwig_bin EXE     the folder holding bitwig.jar: <Bitwig>/bin, or Contents/Java in the app bundle
# bml_real_path FILE     FILE's absolute path with links resolved (readlink -f, which older macOS lacks)
# bml_unquarantine ROOT  macOS: removes the download quarantine from BML's own folder, ROOT (after
#                        that, ROOT/BML.command can be double-clicked in Finder)
# bml_mac_java CONTENTS  macOS: Bitwig's own Java in its app bundle's Contents folder (native arch first)
# bml_runtime_java HOME  HOME/bin/java when HOME is a Java runtime whose release file lists java.desktop
# bml_java_ok JAVA       true when JAVA runs and is Java 21 or later
# bml_bitwig_java [EXE]  the Java runtime inside the Bitwig whose executable is EXE (else the default Bitwig)
# bml_configured_bitwig ROOT  the Bitwig executable chosen in the window's settings (per user, or ROOT/config
#                        in a test install marked portable)
# bml_find_java ROOT [BITWIG_BIN]  the Java for the window and the preparer, in this order: $BML_JAVA; the
#                        runtime of the Bitwig at BITWIG_BIN (preparation); the configured Bitwig's; the
#                        default-location Bitwig's; $JAVA_HOME, PATH and (macOS) java_home, if Java 21+.
#                        Prints nothing and fails when there is none.
# bml_bitwig_pid         this user's Bitwig JVM: on Linux the JVM whose command line names Bitwig's
#                        main class, on macOS the "BitwigStudio --launch" child (the JVM runs inside it)
# bml_bitwig_running     a PID if any Bitwig process of this user runs
# bml_alive PID          true while PID runs
BML_OS=$(uname -s 2>/dev/null || echo unknown)
BML_NATIVE=""
if [ "$BML_OS" = Darwin ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null)" = 1 ]; then BML_NATIVE="arch -arm64"; fi

bml_bitwig_exe() {
  _bml_e=${1:-}
  if [ -z "$_bml_e" ]; then
    if [ "$BML_OS" = Darwin ]; then _bml_e="/Applications/Bitwig Studio.app/Contents/MacOS/BitwigStudio"; else _bml_e=/opt/bitwig-studio/bitwig-studio; fi
  fi
  case "$_bml_e" in *.app|*.app/) if [ -d "$_bml_e" ]; then _bml_e="${_bml_e%/}/Contents/MacOS/BitwigStudio"; fi ;; esac
  printf '%s\n' "$_bml_e"
}

bml_bitwig_bin() {
  if [ "$BML_OS" = Darwin ]; then printf '%s/Java\n' "$(dirname -- "$(dirname -- "$1")")"; else printf '%s/bin\n' "$(dirname -- "$1")"; fi
}

bml_real_path() {
  _bml_p=$(readlink -f -- "$1" 2>/dev/null) && [ -n "$_bml_p" ] && { printf '%s\n' "$_bml_p"; return 0; }
  _bml_p=$1
  _bml_n=0
  while [ -L "$_bml_p" ] && [ "$_bml_n" -lt 40 ]; do
    _bml_t=$(readlink -- "$_bml_p")
    case "$_bml_t" in /*) _bml_p=$_bml_t ;; *) _bml_p=$(dirname -- "$_bml_p")/$_bml_t ;; esac
    _bml_n=$((_bml_n + 1))
  done
  _bml_d=$(CDPATH= cd -- "$(dirname -- "$_bml_p")" 2>/dev/null && pwd -P) || return 1
  printf '%s/%s\n' "$_bml_d" "$(basename -- "$_bml_p")"
}

# macOS marks what a browser or Archive Utility saves as downloaded (com.apple.quarantine) and then
# refuses to load BML's agent into Bitwig (and to open BML.command from Finder). Only BML's own
# folder is changed, once per launch chain.
bml_unquarantine() {
  if [ "$BML_OS" != Darwin ] || [ "${BML_UNQUARANTINED:-}" = "$1" ]; then return 0; fi
  if xattr -lr "$1" 2>/dev/null | grep -q 'com\.apple\.quarantine'; then
    xattr -dr com.apple.quarantine "$1" 2>/dev/null
    echo "macOS: removed the download quarantine from BML's own folder ($1); nothing outside it is changed."
    if xattr -lr "$1" 2>/dev/null | grep -q 'com\.apple\.quarantine'; then
      echo "WARNING: some files in $1 are still quarantined; macOS may refuse to load BML's agent."
    elif [ -f "$1/BML.command" ]; then
      echo "macOS: from now on, double-click BML.command in that folder to start BML; no Terminal line is needed."
    fi
  fi
  BML_UNQUARANTINED=$1
  export BML_UNQUARANTINED
  return 0
}

bml_mac_java() {
  if [ "$BML_NATIVE" != "" ]; then _bml_a="arm64 x64"; else _bml_a="x64 arm64"; fi
  for _bml_b in $_bml_a; do
    if bml_runtime_java "$1/PlugIns/JavaVM-$_bml_b.bundle/Contents/Home"; then return 0; fi
  done
  return 1
}

bml_runtime_java() {
  [ -x "$1/bin/java" ] && grep -q 'java\.desktop' "$1/release" 2>/dev/null && printf '%s\n' "$1/bin/java"
}

bml_java_ok() {
  [ -n "$1" ] && [ -x "$1" ] || return 1
  _bml_v=$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
  [ -n "$_bml_v" ] && [ "$_bml_v" -ge 21 ] 2>/dev/null
}

bml_bitwig_java() {
  _bml_x=$(bml_bitwig_exe "${1:-}")
  if [ "$BML_OS" = Darwin ]; then
    bml_mac_java "$(dirname -- "$(dirname -- "$_bml_x")")"
    return
  fi
  if [ -e "$_bml_x" ]; then _bml_x=$(bml_real_path "$_bml_x") || return 1; fi
  bml_runtime_java "$(dirname -- "$_bml_x")/lib/jre"
}

bml_configured_bitwig() {
  if [ "$BML_OS" = Darwin ]; then
    _bml_s="$HOME/Library/Application Support/BML/bml.properties"
  else
    case "${XDG_CONFIG_HOME:-}" in /*) _bml_s="$XDG_CONFIG_HOME/bml/bml.properties" ;; *) _bml_s="$HOME/.config/bml/bml.properties" ;; esac
  fi
  if [ -f "$1/config/portable" ] || [ ! -f "$_bml_s" ]; then _bml_s="$1/config/bml.properties"; fi
  sed -n 's/^bitwig\.executable=//p' "$_bml_s" 2>/dev/null | head -n 1 | tr -d '\r' | sed 's/\\\(.\)/\1/g'
}

bml_find_java() {
  if [ -n "${BML_JAVA:-}" ] && [ -x "$BML_JAVA" ]; then printf '%s\n' "$BML_JAVA"; return 0; fi
  if [ -n "${2:-}" ]; then
    if [ "$BML_OS" = Darwin ]; then _bml_j=$(bml_mac_java "$2/.." || true); else _bml_j=$(bml_runtime_java "$2/../lib/jre" || true); fi
    if [ -n "$_bml_j" ]; then printf '%s\n' "$_bml_j"; return 0; fi
  fi
  _bml_c=$(bml_configured_bitwig "$1")
  if [ -n "$_bml_c" ]; then
    _bml_j=$(bml_bitwig_java "$_bml_c" || true)
    if [ -n "$_bml_j" ]; then printf '%s\n' "$_bml_j"; return 0; fi
  fi
  _bml_j=$(bml_bitwig_java "" || true)
  if [ -n "$_bml_j" ]; then printf '%s\n' "$_bml_j"; return 0; fi
  if [ -n "${JAVA_HOME:-}" ] && bml_java_ok "$JAVA_HOME/bin/java"; then printf '%s\n' "$JAVA_HOME/bin/java"; return 0; fi
  _bml_j=$(command -v java 2>/dev/null || true)
  if bml_java_ok "$_bml_j"; then printf '%s\n' "$_bml_j"; return 0; fi
  if [ "$BML_OS" = Darwin ] && _bml_h=$(/usr/libexec/java_home 2>/dev/null) && bml_java_ok "$_bml_h/bin/java"; then
    printf '%s\n' "$_bml_h/bin/java"; return 0
  fi
  return 1
}

bml_bitwig_pid() {
  if [ "$BML_OS" = Darwin ]; then
    for _bml_q in $(pgrep -u "$(id -u)" -x BitwigStudio 2>/dev/null); do
      case " $(ps -o args= -p "$_bml_q" 2>/dev/null) " in *" --launch "*) echo "$_bml_q" ;; esac
    done | tail -n 1
    return 0
  fi
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -n -u "$(id -u)" -f 'com\.bitwig\.flt\.app\.BitwigStudioMain'
    return 0
  fi
  for _bml_d in /proc/[0-9]*; do
    [ -O "$_bml_d" ] || continue
    if tr '\0' ' ' <"$_bml_d/cmdline" 2>/dev/null | grep -q 'com\.bitwig\.flt\.app\.BitwigStudioMain'; then echo "${_bml_d#/proc/}"; fi
  done | tail -n 1
}

bml_bitwig_running() {
  if [ "$BML_OS" = Darwin ]; then
    pgrep -u "$(id -u)" -x BitwigStudio 2>/dev/null | head -n 1
    return 0
  fi
  bml_bitwig_pid
}

bml_alive() {
  if [ "$BML_OS" = Darwin ]; then kill -0 "$1" 2>/dev/null; else [ -d "/proc/$1" ]; fi
}
