package ais.simulation.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record StatisticalSummary(
        double mean,
        double median,
        double minimum,
        double maximum,
        double lower95,
        double upper95,
        int sampleCount) {

    public StatisticalSummary {
        if (sampleCount < 1
                || !Double.isFinite(mean)
                || !Double.isFinite(median)
                || !Double.isFinite(minimum)
                || !Double.isFinite(maximum)
                || !Double.isFinite(lower95)
                || !Double.isFinite(upper95)) {
            throw new IllegalArgumentException("invalid statistics");
        }
    }

    public static StatisticalSummary of(List<Double> source) {
        if (source.isEmpty()) {
            return null;
        }
        List<Double> values = new ArrayList<>(source.size());
        for (Double value : source) {
            if (value != null && Double.isFinite(value)) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        Collections.sort(values);
        double mean = values.stream().mapToDouble(Double::doubleValue)
                .average().orElseThrow();
        return new StatisticalSummary(
                mean, quantile(values, 0.5), values.getFirst(),
                values.getLast(), quantile(values, 0.025),
                quantile(values, 0.975), values.size());
    }

    static double quantile(List<Double> sorted, double probability) {
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double position = probability * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = position - lower;
        return sorted.get(lower) * (1.0 - fraction)
                + sorted.get(upper) * fraction;
    }
}
