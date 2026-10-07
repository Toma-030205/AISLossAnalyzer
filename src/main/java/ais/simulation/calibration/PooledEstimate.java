package ais.simulation.calibration;

record PooledEstimate(
        long observedCount,
        long missingCount,
        int distinctVesselCount,
        int observedDayCount,
        Double rawLossRate,
        Double rawReceptionRate,
        Double jeffreysReceptionProbability) {

    long expectedCount() {
        return Math.addExact(observedCount, missingCount);
    }
}
