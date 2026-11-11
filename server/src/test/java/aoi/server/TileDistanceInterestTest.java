package aoi.server;

import aoi.server.algo.TileDistanceInterest;

/** Uses indexMaxAgeMs=0 (exact). The default 10 ms index is covered by GridIndexStalenessFindingTest. */
class TileDistanceInterestTest extends InterestManagerContractTest {
    @Override
    protected InterestManager create() {
        return new TileDistanceInterest(50, 2, 0);
    }
}
