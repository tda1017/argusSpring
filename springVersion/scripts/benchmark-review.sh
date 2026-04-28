#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${ARGUS_BASE_URL:-http://localhost:8080}"
MODE="${ARGUS_BENCH_MODE:-stream}"
REQUESTS="${ARGUS_BENCH_REQUESTS:-6}"
CONCURRENCY="${ARGUS_BENCH_CONCURRENCY:-2}"
PAYLOAD_FILE="${ARGUS_BENCH_PAYLOAD_FILE:-}"

case "$MODE" in
  stream) ENDPOINT="$BASE_URL/api/v1/review/stream" ;;
  sync) ENDPOINT="$BASE_URL/api/v1/review/sync" ;;
  *) echo "ARGUS_BENCH_MODE must be stream or sync" >&2; exit 2 ;;
esac

TMP_PAYLOAD=""
TMP_RESULT="$(mktemp)"
cleanup() {
  [ -n "$TMP_PAYLOAD" ] && rm -f "$TMP_PAYLOAD"
  rm -f "$TMP_RESULT"
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
echo "requests=$REQUESTS concurrency=$CONCURRENCY payload=$PAYLOAD_FILE"
echo "id,http_code,ttfb_seconds,total_seconds" > "$TMP_RESULT"

export ENDPOINT PAYLOAD_FILE TMP_RESULT
seq "$REQUESTS" | xargs -n 1 -P "$CONCURRENCY" sh -c '
  id="$1"
  metrics="$(curl -sS -o /dev/null \
    -H "Content-Type: application/json" \
    -d @"$PAYLOAD_FILE" \
    -w "%{http_code},%{time_starttransfer},%{time_total}" \
    "$ENDPOINT")"
  printf "%s,%s\n" "$id" "$metrics" >> "$TMP_RESULT"
' sh

sort -n -t, -k1 "$TMP_RESULT"
awk -F, '
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
    printf "\nsummary: count=%d failed=%d avg_ttfb=%.3fs max_ttfb=%.3fs avg_total=%.3fs max_total=%.3fs\n",
      count, failed + 0, ttfb / count, max_ttfb, total / count, max_total
  }
' "$TMP_RESULT"
