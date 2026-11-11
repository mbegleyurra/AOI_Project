package aoi.server;

import aoi.server.algo.PathDistanceInterest;

class PathDistanceInterestTest extends InterestManagerContractTest {
    @Override
    protected InterestManager create() {
        return new PathDistanceInterest(Obstacles.generate(1L), 15);
    }
}
