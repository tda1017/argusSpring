#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${ARGUS_BASE_URL:-http://localhost:8080}"
MODE="${ARGUS_BENCH_MODE:-stream}"
REQUESTS="${ARGUS_BENCH_REQUESTS:-6}"
CONCURRENCY="${ARGUS_BENCH_CONCURRENCY:-2}"
PAYLOAD_FILE="${ARGUS_BENCH_PAYLOAD_FILE:-}"
OUTPUT_FILE="${ARGUS_BENCH_OUTPUT:-}"
MAX_TIME="${ARGUS_BENCH_MAX_TIME:-180}"

case "$MODE" in
  stream) ENDPOINT="$BASE_URL/api/v1/review/stream" ;;
  sync) ENDPOINT="$BASE_URL/api/v1/review/sync" ;;
  orchestrated) ENDPOINT="$BASE_URL/api/v1/review/orchestrated" ;;
  *) echo "ARGUS_BENCH_MODE must be stream, sync, or orchestrated" >&2; exit 2 ;;
esac

TMP_PAYLOAD=""
TMP_RESULT="$(mktemp)"
TMP_SORTED="$(mktemp)"
cleanup() {
  [ -n "$TMP_PAYLOAD" ] && rm -f "$TMP_PAYLOAD"
  rm -f "$TMP_RESULT"
  rm -f "$TMP_SORTED"
}
trap cleanup EXIT

if [ -z "$PAYLOAD_FILE" ]; then
  TMP_PAYLOAD="$(mktemp)"
  cat > "$TMP_PAYLOAD" <<'JSON'
{
  "projectId": "local/demo",
  "mrId": "1",
  "codeDiff": "+ public void processUserInput(String input) {\n+     String sql = \"SELECT * FROM users WHERE name = '\" + input + \"'\";\n+     jdbcTemplate.execute(sql);\n+ }"
}
JSON
  PAYLOAD_FILE="$TMP_PAYLOAD"
fi

echo "endpoint=$ENDPOINT"
echo "requests=$REQUESTS concurrency=$CONCURRENCY max_time=${MAX_TIME}s payload=$PAYLOAD_FILE"
echo "id,http_code,ttfb_seconds,total_seconds" > "$TMP_RESULT"

export ENDPOINT PAYLOAD_FILE TMP_RESULT MAX_TIME
SECONDS=0
seq "$REQUESTS" | xargs -n 1 -P "$CONCURRENCY" sh -c '
  id="$1"
  metrics="$(curl -sS --max-time "$MAX_TIME" -o /dev/null \
    -H "Content-Type: application/json" \
    -d @"$PAYLOAD_FILE" \
    -w "%{http_code},%{time_starttransfer},%{time_total}" \
    "$ENDPOINT" || true)"
  if [ -z "$metrics" ]; then
    metrics="000,0,0"
  fi
  printf "%s,%s\n" "$id" "$metrics" >> "$TMP_RESULT"
' sh
WALL_SECONDS="$SECONDS"

sort -n -t, -k1 "$TMP_RESULT" > "$TMP_SORTED"
cat "$TMP_SORTED"

percentile() {
  column="$1"
  percentile="$2"
  values="$(awk -F, -v column="$column" 'NR > 1 && $2 < 400 { print $column }' "$TMP_SORTED" | sort -n)"
  count="$(printf "%s\n" "$values" | awk 'NF { count++ } END { print count + 0 }')"
  if [ "$count" -eq 0 ]; then
    printf "0.000"
    return
  fi
  rank="$(( (count * percentile + 99) / 100 ))"
  printf "%s\n" "$values" | awk -v rank="$rank" 'NF { count++; if (count == rank) { printf "%.3f", $1; exit } }'
}

TTFB_P50="$(percentile 3 50)"
TTFB_P95="$(percentile 3 95)"
TOTAL_P50="$(percentile 4 50)"
TOTAL_P95="$(percentile 4 95)"

SUMMARY="$(awk -F, -v wall="$WALL_SECONDS" -v ttfb_p50="$TTFB_P50" -v ttfb_p95="$TTFB_P95" -v total_p50="$TOTAL_P50" -v total_p95="$TOTAL_P95" '
  NR > 1 {
    count++
    if ($2 >= 400) failed++
    ttfb += $3
    total += $4
    if ($3 > max_ttfb) max_ttfb = $3
    if ($4 > max_total) max_total = $4
  }
  END {
    if (count == 0) exit 1
    if (wall < 1) wall = 1
    printf "summary: count=%d failed=%d throughput=%.2f req/s avg_ttfb=%.3fs p50_ttfb=%.3fs p95_ttfb=%.3fs max_ttfb=%.3fs avg_total=%.3fs p50_total=%.3fs p95_total=%.3fs max_total=%.3fs",
      count, failed + 0, count / wall, ttfb / count, ttfb_p50, ttfb_p95, max_ttfb, total / count, total_p50, total_p95, max_total
  }
' "$TMP_SORTED")"

printf "\n%s\n" "$SUMMARY"

if [ -n "$OUTPUT_FILE" ]; then
  {
    cat "$TMP_SORTED"
    printf "\n%s\n" "$SUMMARY"
  } > "$OUTPUT_FILE"
fi
