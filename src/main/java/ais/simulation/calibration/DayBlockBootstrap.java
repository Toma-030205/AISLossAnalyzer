package ais.simulation.calibration;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

public final class DayBlockBootstrap {

    public ConfidenceInterval estimate(
            List<CalibrationDayRow> rows,
            int iterations,
            long seed) {
        Objects.requireNonNull(rows, "rows");
        if (iterations < 1) {
            throw new IllegalArgumentException(
                    "iterations must be at least one");
        }
        if (rows.isEmpty()) {
            return null;
        }
        double[] samples = new double[iterations];
        SplittableRandom random = new SplittableRandom(seed);
        for (int iteration = 0; iteration < iterations; iteration++) {
            long observed = 0;
            long missing = 0;
            for (int block = 0; block < rows.size(); block++) {
                CalibrationDayRow selected = rows.get(
                        random.nextInt(rows.size()));
                observed = Math.addExact(observed,
                        selected.observedCount());
                missing = Math.addExact(missing,
                        selected.missingCount());
            }
            long expected = Math.addExact(observed, missing);
            samples[iteration] = (observed + 0.5) / (expected + 1.0);
        }
        Arrays.sort(samples);
        int lowerIndex = (int) Math.floor(0.025 * (iterations - 1));
        int upperIndex = (int) Math.ceil(0.975 * (iterations - 1));
        return new ConfidenceInterval(
                samples[lowerIndex], samples[upperIndex]);
    }
}
