# Latency comparison: client knows everyone vs. server tells the client

## What is measured
A probe client (`aoi.client.LatencyProbe`) connects as one player plus the spectator port.
The spectator port reports which entities the server considers visible to that player, tick by tick (ground truth).

- `new_entity_delay_ms`: time the player connection first delivered an entity minus the time the spectator first
  reported it visible to the player. An entity the player already held counts as 0 (counted as "preknown").
- `new_entity_delay_ticks`: the same delay in server ticks (not affected by network jitter).
- `snapshot_skew_ms`: player receipt of tick T minus spectator receipt of tick T.
- `snapshot_interval_ms`: time between player snapshots.
- `snapshot_latency_ms`: client receive time minus the server's per-tick TIMESTAMP message (opt-in, byte 10 request,
  byte 11 reply, sent just before each SNAPSHOT). Valid only when client and server share a clock (same machine).
  Count 0 and NaN if the server sends no timestamps; NaN differences are left blank in the table.

Probe CSV columns: `label,metric,count,mean,median,p95,max` (ms, except `_ticks`).

Modes (same bots, seed, radius, probe duration):
- `all` (`--algo=all`): baseline. The server sends every entity to every client, so the client already knows where
  everyone is. New-entity delay should be about 0.
- `distance` (`--algo=distance`): AOI. The client learns of an entity only when the server sends it.
- `ray` (`--algo=ray`): AOI with line of sight.

The difference column is `<mode> minus all`. Positive means slower than baseline: the extra latency from
not knowing about other players until the server tells you.

## How to run
```
. /home/kali/tools/env.sh
cd /home/kali/Documents/AOI_Project
./run-latency-compare.sh [seconds=20] [bots=300] [radius=150] [seed=42] [port=7777]
```
Optional env: `MODES="all distance"` to pick modes (first is the baseline); `PROBE_ARGS="--still"` to keep the
player stationary. On Windows use `run-latency-compare.cmd` (needs `sh`, e.g. Git for Windows).

The script builds with `mvn -q -DskipTests install`, starts the server per mode, runs the probe, and always stops
the server on exit. Outputs: `bench/latency-compare.csv` (table), `bench/latency-raw.csv` (probe rows),
`bench/logs/` (server and probe logs).

## Reading the numbers
- mean / median: typical delay. p95 / max: tail. Compare the same metric and stat across columns.
- `new_entity_delay_*` is the headline number. Baseline near 0 and AOI above 0 means the AOI delay is real.
- `snapshot_skew_ms` is a sanity check on the measurement itself; if it is large, treat small delays as noise.
- Run to run variance exists (the probe random-walks). Use longer runs (`60`) or `--still` for steadier numbers.

## Limits
- Server is started as `java -cp common/target/classes:server/target/classes aoi.server.ServerMain <bots> <radius>
  <port> <seed> <port+1> --algo=<mode>`; the probe as `java -cp client/target/classes:common/target/classes
  aoi.client.LatencyProbe ...`. If a mode's server fails to start it is skipped; if `all` fails there is no baseline
  and the script exits 2.
- With `--algo=all` the radius argument only sets the drawn AOI circle; every entity is still sent.
- Nothing here has been compiled or run yet by the authors of the script; first run may reveal compile errors.
- The probe takes no seed argument; the server world is seeded identically, the probe walk is not.
- The table parser expects the probe CSV header to contain label/mode, metric/name, mean, median, p95, max columns.
  If it does not, the script exits 3 and the raw files remain.
