#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE="${JVM_PROFILE_SERVICE:-platform-server}"
PID_DIR="${SPACEAGENT_PID_DIR:-$ROOT_DIR/.run/platform}"
PID_FILE="${JVM_PROFILE_PID_FILE:-$PID_DIR/$SERVICE.pid}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_DIR="${JVM_PROFILE_OUTPUT_DIR:-$ROOT_DIR/.run/jvm-profiles/$TIMESTAMP-$SERVICE}"
MAX_PINNED_EVENTS="${JVM_PROFILE_MAX_PINNED_EVENTS:-0}"
MAX_SUBMIT_FAILURES="${JVM_PROFILE_MAX_SUBMIT_FAILURES:-0}"
JFR_FILE="$OUTPUT_DIR/$SERVICE.jfr"
EVENT_FILE="$OUTPUT_DIR/virtual-thread-events.txt"
RECORDING_NAME="spaceagent-$SERVICE-$TIMESTAMP"
recording_active=0

require_bin() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

resolve_jdk_tool() {
  local tool="$1"
  local java_home=""
  if command -v "$tool" >/dev/null 2>&1; then
    command -v "$tool"
    return
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/$tool" ]]; then
    printf '%s\n' "$JAVA_HOME/bin/$tool"
    return
  fi
  java_home="$(java -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/^[[:space:]]*java.home =/ { print $2; exit }')"
  if [[ -n "$java_home" && -x "$java_home/bin/$tool" ]]; then
    printf '%s\n' "$java_home/bin/$tool"
    return
  fi
  echo "Missing required JDK tool: $tool" >&2
  exit 1
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

for binary in java awk grep mkdir; do
  require_bin "$binary"
done
JCMD_BIN="$(resolve_jdk_tool jcmd)"
JFR_BIN="$(resolve_jdk_tool jfr)"
if ! is_non_negative_integer "$MAX_PINNED_EVENTS" \
  || ! is_non_negative_integer "$MAX_SUBMIT_FAILURES"; then
  echo "JVM profile event thresholds must be non-negative integers." >&2
  exit 1
fi
if [[ ! -f "$PID_FILE" ]]; then
  echo "PID file not found: $PID_FILE" >&2
  echo "Start platform-server first or set JVM_PROFILE_PID_FILE." >&2
  exit 1
fi

pid="$(cat "$PID_FILE")"
if [[ -z "$pid" ]] || ! kill -0 "$pid" >/dev/null 2>&1; then
  echo "JVM process is not running: $pid" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

stop_recording() {
  if (( recording_active == 0 )); then
    return
  fi
  "$JCMD_BIN" "$pid" JFR.stop "name=$RECORDING_NAME" "filename=$JFR_FILE" \
    >"$OUTPUT_DIR/jfr-stop.txt" 2>&1 || true
  recording_active=0
}

trap stop_recording EXIT INT TERM

"$JCMD_BIN" "$pid" JFR.start "name=$RECORDING_NAME" settings=profile disk=true maxsize=256m \
  >"$OUTPUT_DIR/jfr-start.txt"
recording_active=1

set +e
if (( $# > 0 )); then
  "$@"
  workload_status=$?
else
  "$ROOT_DIR/scripts/jvm-load-baseline.sh"
  workload_status=$?
fi
set -e

stop_recording
trap - EXIT INT TERM

if [[ ! -s "$JFR_FILE" ]]; then
  echo "JFR recording was not created: $JFR_FILE" >&2
  exit 1
fi

"$JFR_BIN" print \
  --events jdk.VirtualThreadPinned,jdk.VirtualThreadSubmitFailed,jdk.GarbageCollection,jdk.GCPhasePause \
  "$JFR_FILE" >"$EVENT_FILE"

pinned_events="$(grep -c '^jdk.VirtualThreadPinned {' "$EVENT_FILE" || true)"
submit_failures="$(grep -c '^jdk.VirtualThreadSubmitFailed {' "$EVENT_FILE" || true)"
gc_events="$(grep -c '^jdk.GarbageCollection {' "$EVENT_FILE" || true)"

echo
echo "SpaceAgent JVM/JFR profile"
echo "Service:                       $SERVICE"
echo "PID:                           $pid"
echo "VirtualThreadPinned events:    $pinned_events"
echo "VirtualThreadSubmit failures:  $submit_failures"
echo "GarbageCollection events:      $gc_events"
echo "Recording:                     $JFR_FILE"
echo "Printed events:                $EVENT_FILE"

gate_failed=0
if (( pinned_events > MAX_PINNED_EVENTS )); then
  echo "FAIL: pinned virtual-thread events exceed $MAX_PINNED_EVENTS" >&2
  gate_failed=1
fi
if (( submit_failures > MAX_SUBMIT_FAILURES )); then
  echo "FAIL: virtual-thread submit failures exceed $MAX_SUBMIT_FAILURES" >&2
  gate_failed=1
fi
if (( workload_status != 0 )); then
  echo "FAIL: workload exited with status $workload_status" >&2
  gate_failed=1
fi

if (( gate_failed != 0 )); then
  exit 2
fi
