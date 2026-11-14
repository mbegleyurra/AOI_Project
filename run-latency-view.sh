#!/bin/sh
# Visual latency comparison: starts the server and opens a window with two panels for one player.
#   LEFT  = client already knows where the other players are (baseline)
#   RIGHT = client only learns of them when the server sends them, after a simulated one-way delay
# Usage: ./run-latency-view.sh [algo=ray|distance] [delayMs=100] [bots=300] [radius=150] [seed=42] [port=7777]
# Keys in the window: TAB next player, UP/DOWN delay +/-20 ms, 0 = no delay, 4 = 40 ms.
# Needs a display (DISPLAY set) and java + mvn (sources /home/kali/tools/env.sh).

ROOT=$(cd "$(dirname "$0")" && pwd) || exit 1
cd "$ROOT" || exit 1

ALGO=${1:-ray}
DELAY=${2:-100}
BOTS=${3:-300}
RADIUS=${4:-150}
SEED=${5:-42}
PORT=${6:-7777}
SPORT=$((PORT + 1))

[ -f /home/kali/tools/env.sh ] && . /home/kali/tools/env.sh
command -v java >/dev/null 2>&1 || { echo "java not found on PATH" >&2; exit 1; }
command -v mvn  >/dev/null 2>&1 || { echo "mvn not found on PATH" >&2; exit 1; }

SERVER_PID=""
PLAYER_PID=""
stop_server() {
  [ -n "$PLAYER_PID" ] && kill "$PLAYER_PID" 2>/dev/null
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  PLAYER_PID=""
  SERVER_PID=""
}
trap 'stop_server' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "== building"
mvn -q -DskipTests install || { echo "build failed" >&2; exit 1; }

mkdir -p bench/logs
echo "== server --algo=$ALGO on $PORT (spectator $SPORT)"
java -cp "common/target/classes:server/target/classes" aoi.server.ServerMain "$BOTS" "$RADIUS" "$PORT" "$SEED" "$SPORT" "--algo=$ALGO" >bench/logs/server-view.log 2>&1 &
SERVER_PID=$!

i=0
while [ $i -lt 40 ]; do
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "server died, see bench/logs/server-view.log" >&2; exit 1; }
  if command -v nc >/dev/null 2>&1; then nc -z localhost "$SPORT" >/dev/null 2>&1 && break; elif [ $i -ge 12 ]; then break; fi
  sleep 0.25
  i=$((i + 1))
done

# The view follows one connected player, so start a wandering player (the latency probe) for it to follow.
LOSARG=""; [ "$ALGO" = "ray" ] && LOSARG="--los"
echo "== starting a wandering player"
# shellcheck disable=SC2086
java -cp "client/target/classes:common/target/classes" aoi.client.LatencyProbe --host localhost --port "$PORT" --sport "$SPORT" \
  --seconds 3600 --out /tmp/latency-view-probe.csv --label view --view-radius "$RADIUS" $LOSARG >bench/logs/view-player.log 2>&1 &
PLAYER_PID=$!
sleep 2
kill -0 "$PLAYER_PID" 2>/dev/null || { echo "player failed to start, see bench/logs/view-player.log" >&2; exit 1; }

echo "== opening the latency view (close the window to stop)"
mvn -q -pl client javafx:run@latencyview -Djavafx.args="localhost $SPORT $DELAY"
