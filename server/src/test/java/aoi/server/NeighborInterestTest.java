package aoi.server;

import aoi.server.algo.NeighborInterest;

/** Uses indexMaxAgeMs=0 (exact). The default 10 ms index is covered by GridIndexStalenessFindingTest. */
class NeighborInterestTest extends InterestManagerContractTest {
    @Override
    protected InterestManager create() {
        return new NeighborInterest(50, 0);
    }
}
