#!/bin/sh
# Prepares the enabled mods for the installed Bitwig, then starts Bitwig: through the BML agent
# when the prepared runtime needs it, else directly. The Linux and macOS counterpart of
# launch-bitwig.cmd.
#   launch-bitwig.sh [path to Bitwig's launcher, default /opt/bitwig-studio/bitwig-studio;
#                     macOS: Bitwig Studio.app or its Contents/MacOS/BitwigStudio, default the one
#                     in /Applications]
set -u
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CORE="$ROOT/loader"
MODS="$ROOT/mods"
. "$CORE/platform.sh"
bml_unquarantine "$ROOT"
BITWIG_EXE=$(bml_bitwig_exe "${1:-}")
if [ ! -f "$BITWIG_EXE" ]; then
  echo "ERROR: Bitwig launcher not found at:"
  echo "  $BITWIG_EXE"
  if [ "$BML_OS" = Darwin ]; then
    echo "Pass the full path of Bitwig Studio.app as the first argument if installed elsewhere."
  else
    echo "Pass the full path of Bitwig's launcher (bitwig-studio) as the first argument if installed elsewhere."
  fi
  exit 2
fi
# /usr/bin/bitwig-studio is a link: Bitwig's folders are beside the file it points to.
BITWIG_EXE=$(bml_real_path "$BITWIG_EXE")
BITWIG_ROOT=$(dirname -- "$BITWIG_EXE")
BITWIG_BIN=$(bml_bitwig_bin "$BITWIG_EXE")
export BITWIG_EXE

debug() { if [ "${BML_DEBUG_VERBOSE:-}" = "1" ]; then echo "[BML debug] $*"; fi; }

debug "Starting Manifest v2 target resolution, transform preparation and verification..."
sh "$CORE/prepare-mod.sh" "$BITWIG_BIN" "$MODS"
rc=$?
if [ "$rc" -ne 0 ]; then exit "$rc"; fi
debug "Preparation returned successfully; reading runtime metadata..."

info() { sed -n "s/^$1=//p" "$CORE/mod-runtime/build-info.txt" 2>/dev/null | head -n 1 | tr -d '\r'; }
MOD_IDS=$(info mod_ids)
MOD_COUNT=$(info mod_count)
TRANSFORM_COUNT=$(info transform_count)
TRACE_TARGET_COUNT=$(info trace_target_count)
REPLACEMENT_CLASS_COUNT=$(info replacement_class_count)
if [ -z "$TRANSFORM_COUNT" ]; then
  echo "ERROR: Could not read transform_count from loader runtime metadata."
  exit 3
fi
TRACE_TARGET_COUNT=${TRACE_TARGET_COUNT:-0}
REPLACEMENT_CLASS_COUNT=${REPLACEMENT_CLASS_COUNT:-0}

case "${BML_TRACE_ENABLED:-0}" in 1|true|TRUE|True) TRACE_ENABLED=1 ;; *) TRACE_ENABLED=0 ;; esac

# The agent is required for prepared class replacements even with tracing off.
# It is also required for an explicit Debug Mode trace-only launch.
NEED_AGENT=0
if [ "$REPLACEMENT_CLASS_COUNT" != "0" ]; then NEED_AGENT=1; fi
if [ "$TRACE_ENABLED" = "1" ] && [ "$TRACE_TARGET_COUNT" != "0" ]; then NEED_AGENT=1; fi
debug "Prepared mods=$MOD_COUNT transforms=$TRANSFORM_COUNT replacements=$REPLACEMENT_CLASS_COUNT trace_targets=$TRACE_TARGET_COUNT."

CONSOLE_LOG="$ROOT/state/bitwig-console.log"
export BML_BITWIG_CONSOLE_LOG="$CONSOLE_LOG"

if [ "$NEED_AGENT" = "1" ]; then
  debug "Startup JVMTI agent is required for this launch."
  export BITWIG_TRACE_TARGETS="$CORE/mod-runtime/targets.tsv"
  if [ "$REPLACEMENT_CLASS_COUNT" != "0" ]; then
    export BITWIG_CLASS_HOOK_MODE=file
    export BITWIG_REPLACEMENT_MAP="$CORE/mod-runtime/replacement-map.tsv"
  fi
  if [ -f "$CORE/mod-runtime/helper.jar" ]; then export BITWIG_HELPER_JAR="$CORE/mod-runtime/helper.jar"; fi
  export BML_MOD_CONFIG="$CORE/mod-runtime/mod-bindings.properties"
  export BITWIG_MOD_LOG_IN_REPORT=1
  export BML_TRACE_ENABLED="$TRACE_ENABLED"
  debug "Handing prepared runtime to the JVMTI launcher..."
  sh "$CORE/jvmti/launch-bitwig-agent.sh" "$BITWIG_EXE"
  rc=$?
  if [ "$rc" -ne 0 ]; then exit "$rc"; fi
  echo
  echo "Standalone loader active."
  echo "Mods: $MOD_IDS"
  if [ "$TRACE_ENABLED" = "1" ]; then echo "Debug Mode: enabled"; else echo "Debug Mode: off"; fi
  exit 0
fi

echo
debug "No startup JVMTI agent is required for this launch."
debug "Launching Bitwig directly without an injected agent."
if [ "$TRACE_ENABLED" = "1" ] && [ "$TRACE_TARGET_COUNT" = "0" ]; then
  echo "Debug Mode requested, but no trace targets were prepared. Launching without tracing."
fi
echo "No startup JVMTI work is required. Launching Bitwig without the BML agent."
mkdir -p "$(dirname -- "$CONSOLE_LOG")"
: >"$CONSOLE_LOG"
# Detached from this script and from BML (own session), so closing either leaves Bitwig running.
if command -v setsid >/dev/null 2>&1; then
  (cd "$BITWIG_ROOT" && setsid -f "$BITWIG_EXE" </dev/null >>"$CONSOLE_LOG" 2>&1)
else
  # (macOS has no setsid.) BML_NATIVE: arm64 on Apple silicon, as Bitwig's own launch is.
  (cd "$BITWIG_ROOT" && nohup $BML_NATIVE "$BITWIG_EXE" </dev/null >>"$CONSOLE_LOG" 2>&1 &)
fi
if [ "$?" -ne 0 ]; then
  echo "ERROR: Failed to start Bitwig launcher process."
  exit 9
fi
echo "Standalone loader inert mode: Bitwig started without an injected agent."
exit 0
