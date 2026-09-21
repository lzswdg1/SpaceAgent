#!/usr/bin/env bash
set -euo pipefail

TEMPO_URL="${SPACEAGENT_TEMPO_URL:-http://127.0.0.1:3200}"
LOKI_URL="${SPACEAGENT_LOKI_URL:-http://127.0.0.1:3100}"
TRACE_ID="${SPACEAGENT_TRACE_ID:-${1:-}}"
EXPECTED_SERVICES="${SPACEAGENT_TRACE_EXPECTED_SERVICES:-platform-server}"
EXPECTED_SPANS="${SPACEAGENT_TRACE_EXPECTED_SPANS:-model.chat}"
WAIT_SECONDS="${SPACEAGENT_TRACE_WAIT_SECONDS:-60}"
VERIFY_LOGS="${SPACEAGENT_TRACE_VERIFY_LOGS:-true}"

require_bin() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_bin curl
require_bin python3

TRACE_ID="$(printf '%s' "$TRACE_ID" | tr '[:upper:]' '[:lower:]')"
if [[ ! "$TRACE_ID" =~ ^[0-9a-f]{32}$ || "$TRACE_ID" == "00000000000000000000000000000000" ]]; then
  echo "Provide a non-zero 32-character hexadecimal trace ID as SPACEAGENT_TRACE_ID or argument 1." >&2
  exit 1
fi
if [[ ! "$WAIT_SECONDS" =~ ^[0-9]+$ || "$WAIT_SECONDS" -lt 1 ]]; then
  echo "SPACEAGENT_TRACE_WAIT_SECONDS must be a positive integer." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
TRACE_FILE="$TMP_DIR/trace.json"
LOG_FILE="$TMP_DIR/logs.json"
DEADLINE=$((SECONDS + WAIT_SECONDS))
TRACE_VERIFIED=0

echo "Waiting for trace $TRACE_ID in Tempo..."
while (( SECONDS < DEADLINE )); do
  if curl -fsS \
      --connect-timeout 3 \
      --max-time 10 \
      "$TEMPO_URL/api/traces/$TRACE_ID" \
      -o "$TRACE_FILE" 2>/dev/null; then
    if python3 - "$TRACE_FILE" "$EXPECTED_SERVICES" "$EXPECTED_SPANS" <<'PY'
import json
import sys

trace_file, expected_services_csv, expected_spans_csv = sys.argv[1:]
with open(trace_file, encoding="utf-8") as stream:
    payload = json.load(stream)

services = set()
span_names = set()

def visit(value):
    if isinstance(value, dict):
        if value.get("key") == "service.name":
            attribute = value.get("value") or {}
            service_name = attribute.get("stringValue")
            if service_name:
                services.add(service_name)
        if value.get("traceId") and value.get("spanId") and value.get("name"):
            span_names.add(value["name"])
        for nested in value.values():
            visit(nested)
    elif isinstance(value, list):
        for nested in value:
            visit(nested)

visit(payload)

expected_services = {item.strip() for item in expected_services_csv.split(",") if item.strip()}
expected_spans = {item.strip() for item in expected_spans_csv.split(",") if item.strip()}
missing_services = sorted(expected_services - services)
missing_spans = sorted(expected_spans - span_names)

if missing_services or missing_spans:
    print("Observed services:", ", ".join(sorted(services)) or "(none)")
    print("Observed spans:", ", ".join(sorted(span_names)) or "(none)")
    if missing_services:
        print("Missing services:", ", ".join(missing_services))
    if missing_spans:
        print("Missing spans:", ", ".join(missing_spans))
    raise SystemExit(1)

print("Observed services:", ", ".join(sorted(services)))
print("Required spans:", ", ".join(sorted(expected_spans)))
PY
    then
      TRACE_VERIFIED=1
      break
    fi
  fi
  sleep 2
done

if [[ "$TRACE_VERIFIED" != "1" ]]; then
  echo "FAIL: trace $TRACE_ID did not contain all expected services and spans within ${WAIT_SECONDS}s." >&2
  if [[ -s "$TRACE_FILE" ]]; then
    python3 -m json.tool "$TRACE_FILE" 2>/dev/null | sed -n '1,160p' >&2 || true
  fi
  exit 1
fi

if [[ "$VERIFY_LOGS" == "1" || "$VERIFY_LOGS" == "true" ]]; then
  echo "Waiting for trace-correlated logs in Loki..."
  DEADLINE=$((SECONDS + WAIT_SECONDS))
  while (( SECONDS < DEADLINE )); do
    read -r START_NS END_NS < <(python3 - <<'PY'
import time
end = time.time_ns()
print(end - 600_000_000_000, end)
PY
)
    if curl -fsS -G \
        --connect-timeout 3 \
        --max-time 10 \
        --data-urlencode 'query={platform=~"docker|local"} |= "'"$TRACE_ID"'"' \
        --data-urlencode "start=$START_NS" \
        --data-urlencode "end=$END_NS" \
        --data-urlencode "limit=20" \
        "$LOKI_URL/loki/api/v1/query_range" \
        -o "$LOG_FILE" 2>/dev/null \
        && python3 - "$LOG_FILE" "$TRACE_ID" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    payload = json.load(stream)
trace_id = sys.argv[2]
results = ((payload.get("data") or {}).get("result") or [])
lines = [
    line
    for result in results
    for _, line in (result.get("values") or [])
    if trace_id in line
]
if not lines:
    raise SystemExit(1)
print("Trace-correlated log lines:", len(lines))
PY
    then
      echo "PASS: distributed trace propagation, custom spans, and Trace-to-Logs verified"
      exit 0
    fi
    sleep 2
  done
  echo "FAIL: Loki did not receive an application log containing trace ID $TRACE_ID." >&2
  exit 1
fi

echo "PASS: distributed trace propagation and custom spans verified"
