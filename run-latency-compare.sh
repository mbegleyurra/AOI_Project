#!/bin/sh
# Latency comparison: baseline (--algo=all, client already knows every entity)
# versus AOI modes (--algo=distance, --algo=ray, client learns of entities only
# when the server sends them). Same bots, seed, radius, probe duration for every mode.
#
# Usage: ./run-latency-compare.sh [seconds=20] [bots=300] [radius=150] [seed=42] [port=7777]
# Env:   MODES="all distance ray"   modes to run (first one is the baseline)
#        NET_DELAY_MS=N             simulated one-way player-connection delay (ms). Unset = two passes, 0 and 40.
#        WARMUP_TICKS=20            probe warm-up ticks, same for every mode
#        PROBE_ARGS=""              extra args passed to aoi.client.LatencyProbe (e.g. --still)
# Every probe gets --view-radius <radius arg> so all modes are judged against the same geometry.
# Needs: JAVA_HOME / java and mvn on PATH (source /home/kali/tools/env.sh first).
# Probe CSV columns: label,metric,count,mean,median,p95,max (one row per metric per mode).
# Server: aoi.server.ServerMain bots radius port seed spectatorPort --algo=all|distance|ray
# Output: bench/latency-compare.csv, bench/latency-raw.csv, bench/logs/*.log

ROOT=$(cd "$(dirname "$0")" && pwd) || exit 1
cd "$ROOT" || exit 1

SECONDS_RUN=${1:-20}
BOTS=${2:-300}
RADIUS=${3:-150}
SEED=${4:-42}
PORT=${5:-7777}
SPORT=$((PORT + 1))
MODES=${MODES:-"all distance ray"}
PROBE_ARGS=${PROBE_ARGS:-}
WARMUP_TICKS=${WARMUP_TICKS:-20}
if [ -n "${NET_DELAY_MS:-}" ]; then DELAYS="$NET_DELAY_MS"; else DELAYS="0 40"; fi
SUMMARY=bench/latency-summary.csv

[ -f /home/kali/tools/env.sh ] && . /home/kali/tools/env.sh
command -v java >/dev/null 2>&1 || { echo "java not found on PATH (source /home/kali/tools/env.sh)" >&2; exit 1; }
command -v mvn  >/dev/null 2>&1 || { echo "mvn not found on PATH (source /home/kali/tools/env.sh)" >&2; exit 1; }

PROBE_SRC=client/src/main/java/aoi/client/LatencyProbe.java
[ -f "$PROBE_SRC" ] || { echo "missing $PROBE_SRC (copy PLAGUEMASTER's LatencyProbe.java into the repo)" >&2; exit 1; }

mkdir -p bench/logs
RAW=bench/latency-raw.csv
OUT=bench/latency-compare.csv
rm -f "$RAW" "$OUT" "$SUMMARY"

SERVER_PID=""
stop_server() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null
    i=0
    while kill -0 "$SERVER_PID" 2>/dev/null && [ $i -lt 20 ]; do sleep 0.25; i=$((i + 1)); done
    kill -9 "$SERVER_PID" 2>/dev/null
    wait "$SERVER_PID" 2>/dev/null
  fi
  SERVER_PID=""
}
trap 'stop_server' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "== building (mvn -q -DskipTests install)"
mvn -q -DskipTests install || { echo "build failed" >&2; exit 1; }

SERVER_CP="common/target/classes:server/target/classes"
CLIENT_CP="client/target/classes:common/target/classes"
[ -f client/target/classes/aoi/client/LatencyProbe.class ] || { echo "LatencyProbe.class not built; check the client module compiled it" >&2; exit 1; }

wait_for_port() {
  i=0
  while [ $i -lt 60 ]; do
    kill -0 "$SERVER_PID" 2>/dev/null || return 1
    if command -v nc >/dev/null 2>&1; then
      nc -z localhost "$PORT" >/dev/null 2>&1 && nc -z localhost "$SPORT" >/dev/null 2>&1 && return 0
    elif [ $i -ge 12 ]; then
      return 0   # no nc: fall back to a fixed 3s settle
    fi
    sleep 0.25
    i=$((i + 1))
  done
  return 1
}

RAN=""
for mode in $MODES; do
  echo "== mode: $mode (server --algo=$mode, ${SECONDS_RUN}s probe per delay: $DELAYS ms)"
  LOG="bench/logs/server-$mode.log"
  java -cp "$SERVER_CP" aoi.server.ServerMain "$BOTS" "$RADIUS" "$PORT" "$SEED" "$SPORT" "--algo=$mode" >"$LOG" 2>&1 &
  SERVER_PID=$!
  if ! wait_for_port; then
    echo "   SKIPPED: server did not start with --algo=$mode (see $LOG)" >&2
    tail -n 3 "$LOG" >&2
    stop_server
    continue
  fi
  for d in $DELAYS; do
    echo "-- $mode@${d}ms"
    # ray hides entities behind obstacles, so judge it with line of sight; other modes use plain distance
    LOSARG=""; [ "$mode" = "ray" ] && LOSARG="--los"
    # shellcheck disable=SC2086
    java -Duser.language=en -Duser.country=US -cp "$CLIENT_CP" aoi.client.LatencyProbe --host localhost --port "$PORT" --sport "$SPORT" \
      --seconds "$SECONDS_RUN" --out "$RAW" --label "$mode@${d}ms" --view-radius "$RADIUS" \
      --warmup-ticks "$WARMUP_TICKS" --net-delay-ms "$d" $LOSARG $PROBE_ARGS 2>&1 | tee "bench/logs/probe-$mode-${d}ms.log"
  done
  stop_server
  RAN="$RAN $mode"
  sleep 1   # let the port free up before the next mode
done

case " $RAN " in
  *" all "*) ;;
  *) echo "baseline mode 'all' did not run; no comparison possible (see bench/logs/server-all.log)" >&2; exit 2 ;;
esac
[ -s "$RAW" ] || { echo "probe wrote no rows to $RAW" >&2; exit 2; }

# Per delay pass: (1) headline table per mode, (2) full metric table. Columns are located by header name.
FIRST=1
for d in $DELAYS; do
awk -F, -v modes="$RAN" -v out="$OUT" -v sum="$SUMMARY" -v delay="$d" -v first="$FIRST" '
function clean(x) { gsub(/[ "\r]/, "", x); return x }
function isnan(x) { return x == "" || tolower(x) == "nan" }
function fmt(x) { return isnan(x) ? "n/a" : sprintf("%.3f", x) }
function diff(a, b) { return (isnan(a) || isnan(b)) ? "" : sprintf("%.3f", a - b) }
NR == 1 {
  for (i = 1; i <= NF; i++) {
    h = tolower(clean($i))
    if (h == "label" || h == "mode") L = i
    else if (h == "metric" || h == "name") M = i
    else if (h == "count" || h == "n") S["count"] = i
    else if (h == "mean" || h == "mean_ms") S["mean"] = i
    else if (h == "median" || h == "median_ms" || h == "p50") S["median"] = i
    else if (h == "p95" || h == "p95_ms") S["p95"] = i
    else if (h == "max" || h == "max_ms") S["max"] = i
  }
  if (!L || !M || !S["mean"]) { bad = 1; exit 3 }
  next
}
{
  m = clean($M); l = clean($L)
  if (!(m in seen)) { seen[m] = 1; order[++nm] = m }
  for (s in S) val[m, l, s] = clean($S[s])
}
END {
  if (bad) exit 3
  n = split(modes, md, " ")
  stats[1] = "count"; stats[2] = "mean"; stats[3] = "median"; stats[4] = "p95"; stats[5] = "max"
  for (j = 1; j <= n; j++) lab[j] = md[j] "@" delay "ms"
  base = lab[1]; E = "new_entity_delay_ms"
  # headline
  printf "\n=== net delay %s ms: new-entity delay (visible to viewer -> client has data), ms ===\n", delay
  printf "%-16s %9s %10s %10s %10s %10s %14s\n", "mode", "preknown", "mean", "median", "p95", "max", "mean-vs-all"
  if (first == 1) print "net_delay_ms,mode,samples,preknown,mean,median,p95,max,mean_minus_all,median_minus_all" > sum
  for (j = 1; j <= n; j++) {
    l = lab[j]
    pk = val["preknown_count", l, "count"]; cnt = val[E, l, "count"]
    dm = diff(val[E, l, "mean"], val[E, base, "mean"])
    dd = diff(val[E, l, "median"], val[E, base, "median"])
    printf "%-16s %9s %10s %10s %10s %10s %14s\n", l, pk, fmt(val[E, l, "mean"]), fmt(val[E, l, "median"]), fmt(val[E, l, "p95"]), fmt(val[E, l, "max"]), (j == 1 ? "(baseline)" : (dm == "" ? "n/a" : dm))
    print delay "," md[j] "," cnt "," pk "," val[E, l, "mean"] "," val[E, l, "median"] "," val[E, l, "p95"] "," val[E, l, "max"] "," dm "," dd >> sum
  }
  for (j = 2; j <= n; j++) {
    l = lab[j]; a = val[E, l, "mean"]; b = val[E, base, "mean"]
    if (isnan(a) || isnan(b)) printf "CONCLUSION (%sms): %s: no new-entity samples, cannot compare to the client-known baseline.\n", delay, md[j]
    else if (a - b > 0.05) printf "CONCLUSION (%sms): %s adds %.2f ms mean delay (median %s ms) over the client-known baseline (%.2f ms vs %.2f ms).\n", delay, md[j], a - b, diff(val[E, l, "median"], val[E, base, "median"]), a, b
    else printf "CONCLUSION (%sms): %s shows no measurable added delay over the client-known baseline (%.2f ms vs %.2f ms; difference %.3f ms).\n", delay, md[j], a, b, a - b
  }
  # full table
  line = "net_delay_ms,metric,stat"
  for (j = 1; j <= n; j++) line = line "," md[j]
  for (j = 2; j <= n; j++) line = line "," md[j] "_minus_" md[1]
  if (first == 1) print line > out
  printf "\n%-26s %-7s", "metric (" delay "ms)", "stat"
  for (j = 1; j <= n; j++) printf " %12s", md[j]
  for (j = 2; j <= n; j++) printf " %14s", substr(md[j] "-" md[1], 1, 14)
  printf "\n"
  for (k = 1; k <= nm; k++) for (t = 1; t <= 5; t++) {
    m = order[k]; s = stats[t]
    if (!(s in S)) continue
    if (m ~ /_count$/ && s != "count") continue   # count-only metrics carry NaN in the other stats
    line = delay "," m "," s
    printf "%-26s %-7s", m, s
    for (j = 1; j <= n; j++) { x = val[m, lab[j], s]; line = line "," x; printf " %12s", x }
    for (j = 2; j <= n; j++) {
      a = val[m, lab[j], s]; b = val[m, base, s]
      dv = (s == "count") ? "" : diff(a, b)
      line = line "," dv; printf " %14s", dv
    }
    printf "\n"
    print line >> out
  }
}' "$RAW"
RC=$?
if [ $RC -eq 3 ]; then
  echo "could not parse $RAW header; raw per-mode results are in $RAW and bench/logs/probe-*.log" >&2
  exit 3
fi
FIRST=0
done
echo
echo "saved: $SUMMARY (headline), $OUT (all metrics), $RAW (raw probe rows)"
echo "differences are <mode> minus all (baseline); positive = slower than the client-known baseline. Units: ms, except *_ticks."
echo "Rows labeled <mode>@<N>ms use N ms of simulated one-way network delay on the player connection."
