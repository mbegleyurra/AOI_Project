package aoi.server;

import aoi.server.algo.SquareInterest;

class SquareInterestTest extends InterestManagerContractTest {
    @Override
    protected InterestManager create() {
        return new SquareInterest(100);
    }
}
