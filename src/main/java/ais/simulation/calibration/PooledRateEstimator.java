package ais.simulation.calibration;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PooledRateEstimator {

    PooledEstimate estimate(List<CalibrationDayRow> rows) {
        Objects.requireNonNull(rows, "rows");
        long observed = 0;
        long missing = 0;
        Set<Integer> mmsis = new HashSet<>();
        int observedDays = 0;
        for (CalibrationDayRow row : rows) {
            observed = Math.addExact(observed, row.observedCount());
            missing = Math.addExact(missing, row.missingCount());
            mmsis.addAll(row.mmsis());
            if (row.expectedCount() > 0) {
                observedDays++;
            }
        }
        long expected = Math.addExact(observed, missing);
        if (expected == 0) {
            return new PooledEstimate(
                    observed, missing, mmsis.size(), observedDays,
                    null, null, null);
        }
        double rawLoss = missing / (double) expected;
        double rawReception = observed / (double) expected;
        double jeffreysReception = (observed + 0.5) / (expected + 1.0);
        return new PooledEstimate(
                observed, missing, mmsis.size(), observedDays,
                rawLoss, rawReception, jeffreysReception);
    }
}
