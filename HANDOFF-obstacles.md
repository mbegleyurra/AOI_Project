# Obstacles + collision handoff (for LICH)

## What exists
One authoritative model: `aoi.server.Obstacles` (server module). Storage is a cell grid; rectangles are
derived from it (merged cell runs, cached), so grid and rectangle queries cannot disagree.
Treat an instance as immutable once handed to a `World`.

Game layout: `Obstacles.generateRects(seed)` = 24 solid axis-aligned rects, 40..120 units per side,
10-unit cells, >= 3 cells apart and from the world edge (free space stays connected, spawns cannot be
walled in). `ServerMain` builds it from the same seed for both `--algo=distance` and `--algo=ray`;
`--obstacles=N` changes the count, `--obstacles=0` is open terrain.
The old hollow-building `Obstacles.generate(seed)` is untouched (tests and the bench registry use it).

## Query API to use for ray visibility
- `boolean segmentBlocked(x1, y1, x2, y2)`: exact segment-vs-rectangle test. True if the segment touches
  any obstacle (closed rects; an endpoint inside or on an obstacle counts as blocked). O(rects()) per call,
  cheap for the 24-rect layout, slower for the legacy layout (hundreds of merged rects).
- `List<Obstacles.Rect> rects()` / `float[] rectsAsFloats()`: the rectangles (`Rect(x, y, w, h)`, world units).
- `boolean circleOverlaps(cx, cy, r)`, `boolean resolveCircle(double[]{x,y,nx,ny}, r)`: collision helpers.
- Existing grid API still works and agrees with the rects: `isBlocked`, `blockedCell`, `lineOfSight`,
  `cellX/cellY/cellSize/cols/rows`.
- Viewers are never inside an obstacle on the game layout (spawns and collision guarantee it), so the
  "viewer stands inside a wall" case only arises with the legacy layout or hand-built maps.

## Collision (World.step)
Every entity is a circle, `World.ENTITY_RADIUS = 3.0`. Movement is sub-stepped (<= radius/2 per sub-step,
so no tunnelling even with a huge dt) and resolved circle-vs-AABB, which slides along edges. Bots reflect
off the surface; players keep their input velocity and slide. Spawns (`spawnPlayer`, `setObstacles`) are
rejected if the point is in a wall or the circle overlaps an obstacle. Without obstacles `step` behaves as before.

## Wire protocol (documented in `Protocol.java`)
`OBSTACLE_RECTS` = byte 9, `int count`, `count * (float x, float y, float w, float h)`.
Sent once, directly after `WELCOME` (players) and `SPECTATE_WELCOME` (spectators), before the first snapshot,
and only when the world has obstacles (a server without obstacles sends the exact legacy sequence).
Helpers: `Protocol.writeObstacleRects` / `readObstacleRects`. Bytes 6/7/8 belong to the spectator detail
extension (`OBSTACLE_MAP` cell bitmap, still sent on request); the new message is independent of it.
Clients built before this change reject byte 9, so run matching client builds against an obstacle server.

## Clients
`Connection.rects()` and `SpectatorConnection.rects()` hold the received rectangles. `ClientApp` draws them
as filled rectangles; `ObserverApp` draws them too and falls back to its cell-grid map if none arrived.

## Tests
`ObstacleCollisionTest` (14): layout size/determinism/connectivity, rect-grid agreement, spawn validity,
no entity inside an obstacle over 1500 ticks x 3 seeds, slide, head-on stop, thinnest wall at max speed,
huge-dt tunnelling, bot bounce, resolveCircle, segmentBlocked.
`ObstacleMessageTest` (6): codec round trip, type-byte uniqueness, malformed input, delivery on the player
and spectator sockets, and no extra message when there are no obstacles.
