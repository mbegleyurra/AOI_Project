package aoi.server;

import aoi.server.algo.HexagonalInterest;

class HexagonalInterestTest extends InterestManagerContractTest {
    @Override
    protected InterestManager create() {
        return new HexagonalInterest(50, 2);
    }
}
