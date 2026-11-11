package aoi.server;

import aoi.server.algo.TileVisibilityInterest;

class TileVisibilityInterestTest extends InterestManagerContractTest {
    @Override
    protected InterestManager create() {
        return new TileVisibilityInterest(Obstacles.generate(1L), 150);
    }
}
