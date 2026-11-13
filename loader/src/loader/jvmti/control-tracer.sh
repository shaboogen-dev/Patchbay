#!/bin/sh
# Sends status, start or stop to the Debug Mode tracer of the running BML session.
# The Linux and macOS counterpart of control-tracer.cmd.
#   control-tracer.sh status|start|stop
# The tracer acts on a JVMTI DataDumpRequest. jcmd (from a JDK, on PATH or in JAVA_HOME; on
# macOS in JAVA_HOME or the JDK /usr/libexec/java_home names) sends one; without it the JVM's
# own SIGQUIT handler does (it also prints a thread dump to Bitwig's console log), unless the
# JVM runs with -Xrs, where SIGQUIT would end it.
set -u
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$HERE/../platform.sh"
ACTION=${1:-}
case "$ACTION" in status|start|stop) ;; *)
  echo "Syntax: control-tracer.sh status|start|stop"
  exit 2 ;;
esac
ACTIVE="$HERE/active-session.txt"
if [ ! -f "$ACTIVE" ]; then
  echo "ERROR: No active-session.txt. Launch Bitwig with launch-bitwig.sh first."
  exit 3
fi
value() { sed -n "s/^$1=//p" "$ACTIVE" | head -n 1 | tr -d '\r'; }
SAVED_PID=$(value pid)
CONTROL=$(value control)
TRACE=$(value trace)
if [ -z "$CONTROL" ]; then
  echo "ERROR: Active session has no control path."
  exit 5
fi

numeric() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
# Rediscover the Bitwig JVM every time rather than trusting a saved PID alone.
PID=$(bml_bitwig_pid)
if ! numeric "$PID"; then PID=$SAVED_PID; fi
if ! numeric "$PID" || ! bml_alive "$PID"; then
  echo "ERROR: Could not resolve a running Bitwig JVM PID."
  echo "Saved PID in active-session.txt: $SAVED_PID"
  exit 4
fi

if [ "$BML_OS" = Darwin ]; then
  # /usr/bin/jcmd is only a stub when no JDK is installed.
  JCMD=""
  JH=${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}
  if [ -n "$JH" ] && [ -x "$JH/bin/jcmd" ]; then JCMD="$JH/bin/jcmd"; fi
else
  JCMD=$(command -v jcmd || true)
  if [ -z "$JCMD" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jcmd" ]; then JCMD="$JAVA_HOME/bin/jcmd"; fi
fi

echo "Using Bitwig JVM PID: $PID"
echo "$ACTION" >"$CONTROL"
if [ -n "$JCMD" ]; then
  "$JCMD" "$PID" JVMTI.data_dump || exit $?
else
  # (macOS: Bitwig's JVM options are built into its executable, without -Xrs.)
  if [ -r "/proc/$PID/cmdline" ] && tr '\0' '\n' <"/proc/$PID/cmdline" | grep -q -x -e '-Xrs' -e '-XX:+ReduceSignalUsage'; then
    echo "ERROR: Bitwig's JVM runs with -Xrs, so SIGQUIT would end it; install a JDK for jcmd."
    exit 6
  fi
  kill -QUIT "$PID" || exit $?
fi
sleep 1
if [ -f "$CONTROL" ]; then cat "$CONTROL"; fi
if [ "$ACTION" = "stop" ]; then echo "Trace: $TRACE"; fi
exit 0
