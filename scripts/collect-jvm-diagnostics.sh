#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_DIR="${JVM_DIAGNOSTICS_OUTPUT_DIR:-$ROOT_DIR/.run/jvm-snapshots/$TIMESTAMP}"
PID_DIR="${SPACEAGENT_PID_DIR:-$ROOT_DIR/.run/platform}"

SERVICES=(
  "platform-server:9000:spaceagent-platform-server"
)

mkdir -p "$OUTPUT_DIR"

collect_local_service() {
  local name="$1"
  local port="$2"
  local service_dir="$OUTPUT_DIR/$name"
  local pid_file="$PID_DIR/$name.pid"
  local pid=""

  mkdir -p "$service_dir"
  curl -fsS --connect-timeout 2 --max-time 10 "http://127.0.0.1:$port/actuator/health" \
    >"$service_dir/health.json" 2>"$service_dir/health.error" || true
  curl -fsS --connect-timeout 2 --max-time 15 "http://127.0.0.1:$port/actuator/prometheus" \
    >"$service_dir/prometheus.txt" 2>"$service_dir/prometheus.error" || true

  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
  fi
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1 && command -v jcmd >/dev/null 2>&1; then
    jcmd "$pid" VM.command_line >"$service_dir/vm-command-line.txt" 2>&1 || true
    jcmd "$pid" VM.flags >"$service_dir/vm-flags.txt" 2>&1 || true
    jcmd "$pid" GC.heap_info >"$service_dir/heap-info.txt" 2>&1 || true
    jcmd "$pid" Thread.print -l >"$service_dir/thread-dump.txt" 2>&1 || true
    jcmd "$pid" Thread.dump_to_file -overwrite -format=json "$service_dir/thread-dump.json" \
      >"$service_dir/thread-dump-json-command.txt" 2>&1 || true
    jcmd "$pid" JFR.check >"$service_dir/jfr-status.txt" 2>&1 || true
  fi
}

collect_container_service() {
  local name="$1"
  local port="$2"
  local container="$3"
  local service_dir="$OUTPUT_DIR/$name"

  if ! command -v docker >/dev/null 2>&1; then
    return
  fi
  if ! docker inspect "$container" >/dev/null 2>&1; then
    return
  fi

  docker inspect "$container" >"$service_dir/container-inspect.json" 2>&1 || true
  docker stats --no-stream --format '{{json .}}' "$container" >"$service_dir/container-stats.json" 2>&1 || true
  if [[ ! -s "$service_dir/prometheus.txt" ]]; then
    docker exec "$container" curl -fsS "http://localhost:$port/actuator/prometheus" \
      >"$service_dir/prometheus.txt" 2>"$service_dir/prometheus.error" || true
  fi
  mkdir -p "$service_dir/container-diagnostics"
  docker cp "$container:/app/diagnostics/." "$service_dir/container-diagnostics" >/dev/null 2>&1 || true
}

for entry in "${SERVICES[@]}"; do
  IFS=: read -r name port container <<<"$entry"
  collect_local_service "$name" "$port"
  collect_container_service "$name" "$port" "$container"
done

if command -v docker >/dev/null 2>&1; then
  docker stats --no-stream >"$OUTPUT_DIR/docker-stats.txt" 2>&1 || true
fi

echo "JVM diagnostics collected at: $OUTPUT_DIR"
