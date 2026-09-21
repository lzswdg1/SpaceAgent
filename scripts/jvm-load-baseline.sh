#!/usr/bin/env bash
set -euo pipefail

URL="${JVM_BASELINE_URL:-http://127.0.0.1:9000/actuator/health}"
METHOD="${JVM_BASELINE_METHOD:-GET}"
REQUESTS="${JVM_BASELINE_REQUESTS:-200}"
CONCURRENCY="${JVM_BASELINE_CONCURRENCY:-10}"
WARMUP_REQUESTS="${JVM_BASELINE_WARMUP_REQUESTS:-50}"
WARMUP_CONCURRENCY="${JVM_BASELINE_WARMUP_CONCURRENCY:-5}"
CONNECT_TIMEOUT="${JVM_BASELINE_CONNECT_TIMEOUT_SECONDS:-2}"
MAX_TIME="${JVM_BASELINE_MAX_TIME_SECONDS:-15}"
AUTH_TOKEN="${JVM_BASELINE_AUTH_TOKEN:-}"
EXTRA_HEADERS="${JVM_BASELINE_EXTRA_HEADERS:-}"
CONTENT_TYPE="${JVM_BASELINE_CONTENT_TYPE:-application/json}"
BODY="${JVM_BASELINE_BODY:-}"
MAX_ERROR_RATE_PERCENT="${JVM_BASELINE_MAX_ERROR_RATE_PERCENT:-0}"
MAX_P95_SECONDS="${JVM_BASELINE_MAX_P95_SECONDS:-}"
MAX_P99_SECONDS="${JVM_BASELINE_MAX_P99_SECONDS:-}"
MIN_THROUGHPUT="${JVM_BASELINE_MIN_THROUGHPUT:-}"
RESULT_FILE="${JVM_BASELINE_RESULT_FILE:-}"

require_bin() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

is_positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

is_non_negative_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

for binary in curl awk sort seq xargs mktemp date wc tail tr; do
  require_bin "$binary"
done

if ! is_positive_integer "$REQUESTS" || ! is_positive_integer "$CONCURRENCY"; then
  echo "JVM_BASELINE_REQUESTS and JVM_BASELINE_CONCURRENCY must be positive integers." >&2
  exit 1
fi
if ! is_non_negative_integer "$WARMUP_REQUESTS" || ! is_positive_integer "$WARMUP_CONCURRENCY"; then
  echo "JVM_BASELINE_WARMUP_REQUESTS must be non-negative and warmup concurrency must be positive." >&2
  exit 1
fi
for threshold in "$MAX_ERROR_RATE_PERCENT" "$MAX_P95_SECONDS" "$MAX_P99_SECONDS" "$MIN_THROUGHPUT"; do
  if [[ -n "$threshold" ]] && ! is_non_negative_number "$threshold"; then
    echo "Performance thresholds must be non-negative numbers." >&2
    exit 1
  fi
done

result_file="$(mktemp "${TMPDIR:-/tmp}/spaceagent-jvm-results.XXXXXX")"
latency_file="$(mktemp "${TMPDIR:-/tmp}/spaceagent-jvm-latency.XXXXXX")"
warmup_file="$(mktemp "${TMPDIR:-/tmp}/spaceagent-jvm-warmup.XXXXXX")"
trap 'rm -f "$result_file" "$latency_file" "$warmup_file"' EXIT

export URL METHOD CONNECT_TIMEOUT MAX_TIME AUTH_TOKEN EXTRA_HEADERS CONTENT_TYPE BODY

now_seconds() {
  local value
  value="$(date +%s.%N 2>/dev/null || true)"
  if [[ "$value" != *N* ]]; then
    printf '%s\n' "$value"
    return
  fi
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf "%.9f\n", time'
    return
  fi
  date +%s
}

run_load() {
  local requests="$1"
  local concurrency="$2"
  local destination="$3"
  : >"$destination"
  if (( requests == 0 )); then
    return
  fi
  seq "$requests" | xargs -P "$concurrency" -I '{}' bash -c '
    args=(
      --silent
      --output /dev/null
      --write-out "%{http_code} %{time_total} %{size_download}\n"
      --connect-timeout "$CONNECT_TIMEOUT"
      --max-time "$MAX_TIME"
      --compressed
      --request "$METHOD"
    )
    if [[ -n "$AUTH_TOKEN" ]]; then
      args+=(--header "Authorization: Bearer $AUTH_TOKEN")
    fi
    if [[ -n "$EXTRA_HEADERS" ]]; then
      while IFS= read -r header; do
        if [[ -n "$header" ]]; then
          args+=(--header "$header")
        fi
      done <<<"$EXTRA_HEADERS"
    fi
    if [[ -n "$BODY" ]]; then
      args+=(--header "Content-Type: $CONTENT_TYPE" --data "$BODY")
    fi
    result="$(curl "${args[@]}" "$URL" 2>/dev/null || true)"
    if [[ -n "$result" ]]; then
      printf "%s\n" "$result"
    else
      printf "000 0 0\n"
    fi
  ' >>"$destination"
}

if (( WARMUP_REQUESTS > 0 )); then
  echo "Warming up with $WARMUP_REQUESTS requests at concurrency $WARMUP_CONCURRENCY..."
  run_load "$WARMUP_REQUESTS" "$WARMUP_CONCURRENCY" "$warmup_file"
fi

started_at_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_at="$(now_seconds)"
run_load "$REQUESTS" "$CONCURRENCY" "$result_file"
finished_at="$(now_seconds)"
elapsed="$(awk -v start="$started_at" -v finish="$finished_at" \
  'BEGIN { value = finish - start; if (value < 0.000001) value = 0.000001; printf "%.6f", value }')"

awk '$1 ~ /^[23][0-9][0-9]$/ { print $2 }' "$result_file" | sort -n >"$latency_file"

percentile() {
  local percentile="$1"
  awk -v percentile="$percentile" '
    { values[NR] = $1 }
    END {
      if (NR == 0) {
        print 0
        exit
      }
      rank = int(NR * percentile)
      if (rank < NR * percentile) {
        rank++
      }
      if (rank < 1) {
        rank = 1
      }
      print values[rank]
    }
  ' "$latency_file"
}

completed="$(wc -l <"$result_file" | tr -d ' ')"
successful="$(awk '$1 ~ /^[23][0-9][0-9]$/ { count++ } END { print count + 0 }' "$result_file")"
failed=$((completed - successful))
average="$(awk '{ sum += $1 } END { if (NR == 0) print 0; else printf "%.6f", sum / NR }' "$latency_file")"
maximum="$(tail -n 1 "$latency_file")"
maximum="${maximum:-0}"
p50="$(percentile 0.50)"
p95="$(percentile 0.95)"
p99="$(percentile 0.99)"
throughput="$(awk -v successful="$successful" -v elapsed="$elapsed" \
  'BEGIN { printf "%.2f", successful / elapsed }')"
error_rate="$(awk -v failed="$failed" -v completed="$completed" \
  'BEGIN { if (completed == 0) print 100; else printf "%.3f", failed * 100 / completed }')"
downloaded_bytes="$(awk '{ sum += $3 } END { printf "%.0f", sum + 0 }' "$result_file")"

echo "SpaceAgent JVM baseline"
echo "URL:         $METHOD $URL"
echo "Requests:    $completed (success=$successful, failed=$failed)"
echo "Concurrency: $CONCURRENCY"
echo "Wall time:   ${elapsed}s"
echo "Throughput:  ${throughput} successful req/s"
echo "Error rate:  ${error_rate}%"
echo "Downloaded:  ${downloaded_bytes} bytes"
echo "Latency avg: ${average}s"
echo "Latency p50: ${p50}s"
echo "Latency p95: ${p95}s"
echo "Latency p99: ${p99}s"
echo "Latency max: ${maximum}s"
echo
echo "HTTP status distribution:"
awk '{ count[$1]++ } END { for (status in count) print status, count[status] }' "$result_file" | sort

if [[ -n "$RESULT_FILE" ]]; then
  mkdir -p "$(dirname "$RESULT_FILE")"
  if [[ ! -s "$RESULT_FILE" ]]; then
    printf 'timestamp\tmethod\turl\trequests\tconcurrency\tsuccessful\tfailed\terror_rate_percent\telapsed_seconds\tthroughput_rps\tavg_seconds\tp50_seconds\tp95_seconds\tp99_seconds\tmax_seconds\tdownloaded_bytes\n' >"$RESULT_FILE"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$started_at_iso" "$METHOD" "$URL" "$completed" "$CONCURRENCY" "$successful" "$failed" \
    "$error_rate" "$elapsed" "$throughput" "$average" "$p50" "$p95" "$p99" "$maximum" \
    "$downloaded_bytes" >>"$RESULT_FILE"
  echo
  echo "Result appended to: $RESULT_FILE"
fi

gate_failed=0
check_maximum() {
  local label="$1"
  local actual="$2"
  local maximum_allowed="$3"
  if [[ -n "$maximum_allowed" ]] && awk -v actual="$actual" -v maximum="$maximum_allowed" \
    'BEGIN { exit !(actual > maximum) }'; then
    echo "FAIL: $label $actual exceeds maximum $maximum_allowed" >&2
    gate_failed=1
  fi
}

check_maximum "error rate (%)" "$error_rate" "$MAX_ERROR_RATE_PERCENT"
check_maximum "P95 latency (s)" "$p95" "$MAX_P95_SECONDS"
check_maximum "P99 latency (s)" "$p99" "$MAX_P99_SECONDS"
if [[ -n "$MIN_THROUGHPUT" ]] && awk -v actual="$throughput" -v minimum="$MIN_THROUGHPUT" \
  'BEGIN { exit !(actual < minimum) }'; then
  echo "FAIL: throughput $throughput is below minimum $MIN_THROUGHPUT" >&2
  gate_failed=1
fi

if (( gate_failed != 0 )); then
  exit 2
fi
