package aoi.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Wire protocol (big-endian, via DataInput/DataOutput streams).
 *
 * PLAYER connection (main port, default 7777) - unchanged:
 * Server -> client:
 *   WELCOME  : byte 1, int clientId, float worldW, float worldH, float interestRadius
 *   SNAPSHOT : byte 2, int tick, int count, count * (int id, float x, float y)
 * Client -> server:
 *   INPUT    : byte 3, float dx, float dy   (each in -1..1)
 *
 * SPECTATOR connection (separate port, default main port + 1; added later, backward compatible).
 * A spectator never spawns an avatar, is never counted as a player, sends nothing, and receives
 * the UNFILTERED world every tick. Players keep using the main port exactly as before.
 * Server -> spectator:
 *   SPECTATE_WELCOME : byte 4, float worldW, float worldH, float interestRadius
 *   FULL_SNAPSHOT    : byte 5, int tick,
 *                      int count, count * (int id, float x, float y, byte isPlayer 0/1),
 *                      int players, players * (int playerId, int visibleCount, visibleCount * int entityId)
 *   The per-player id lists are exactly the entities that were sent to that player in the SNAPSHOT
 *   of the same tick (the server reuses the InterestManager result, it does not recompute it).
 *
 * SPECTATOR DETAIL extension (opt-in; a spectator that never sends the request behaves exactly as above):
 * Spectator -> server:
 *   SPECTATE_REQUEST_DETAIL : byte 6
 * Server -> that spectator, right after the request, then FULL_SNAPSHOT is replaced by DETAIL_SNAPSHOT:
 *   OBSTACLE_MAP    : byte 7, float cellSize, int cols, int rows, ceil(cols*rows/8) bytes of wall bits
 *                     (cell index = y*cols + x, bit (index&7) of byte (index>>3), LSB first).
 *                     cols = rows = 0 and no bytes when the server has no obstacles.
 *   DETAIL_SNAPSHOT : byte 8, int tick,
 *                     int count, count * (int id, float x, float y, byte isPlayer 0/1),
 *                     int players, players * (int playerId,
 *                                             int sentCount, sentCount * int entityId,
 *                                             int occludedCount, occludedCount * int entityId)
 *   sent = same list as FULL_SNAPSHOT. occluded = bots within the interest radius of the player that
 *   were NOT sent and whose line of sight from the player is blocked by the server's obstacle map.
 *
 * OBSTACLE RECTS (added with collision; backward compatible; sent on BOTH connection types):
 *   OBSTACLE_RECTS : byte 9, int count, count * (float x, float y, float w, float h)
 *   Axis-aligned rectangles covering [x, x+w] x [y, y+h] in world units. These are the server's
 *   collision obstacles. Sent exactly once, immediately after WELCOME (player) or SPECTATE_WELCOME
 *   (spectator), before the first SNAPSHOT/FULL_SNAPSHOT, and only when the server has an obstacle map;
 *   a server without obstacles sends nothing extra, so existing clients keep working against it.
 *   The map is static for the life of the server. Clients that predate this message reject the unknown
 *   type byte, so run matching client builds against an obstacle server. Independent of OBSTACLE_MAP
 *   (byte 7), which is the opt-in cell-bitmap used by the spectator detail view.
 *   Use writeObstacleRects / readObstacleRects (the latter after the type byte has been consumed).
 */
public final class Protocol {
    public static final byte WELCOME = 1;
    public static final byte SNAPSHOT = 2;
    public static final byte INPUT = 3;
    public static final byte SPECTATE_WELCOME = 4;
    public static final byte FULL_SNAPSHOT = 5;
    public static final byte SPECTATE_REQUEST_DETAIL = 6;
    public static final byte OBSTACLE_MAP = 7;
    public static final byte DETAIL_SNAPSHOT = 8;
    public static final byte OBSTACLE_RECTS = 9;

    public static final int DEFAULT_PORT = 7777;
    public static final int DEFAULT_SPECTATOR_PORT = 7778;

    /** Bytes of one entity record inside a snapshot. */
    public static final int ENTITY_BYTES = 12;

    private static final int MAX_OBSTACLE_RECTS = 100_000;

    /** Writes a complete OBSTACLE_RECTS message (type byte included). rects = x,y,w,h per obstacle. */
    public static void writeObstacleRects(DataOutput out, float[] rects) throws IOException {
        if (rects.length % 4 != 0) {
            throw new IllegalArgumentException("rects length must be a multiple of 4");
        }
        out.writeByte(OBSTACLE_RECTS);
        out.writeInt(rects.length / 4);
        for (float f : rects) {
            out.writeFloat(f);
        }
    }

    /** Reads the body of an OBSTACLE_RECTS message; the type byte has already been consumed. */
    public static float[] readObstacleRects(DataInput in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_OBSTACLE_RECTS) {
            throw new IOException("implausible obstacle count " + n);
        }
        float[] rects = new float[n * 4];
        for (int i = 0; i < rects.length; i++) {
            rects[i] = in.readFloat();
        }
        return rects;
    }

    private Protocol() {
    }
}
