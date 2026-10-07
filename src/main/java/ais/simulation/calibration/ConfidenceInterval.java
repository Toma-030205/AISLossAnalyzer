package ais.simulation.calibration;

public record ConfidenceInterval(double lower, double upper) {

    public ConfidenceInterval {
        if (!Double.isFinite(lower) || !Double.isFinite(upper)
                || lower < 0.0 || upper > 1.0 || upper < lower) {
            throw new IllegalArgumentException(
                    "confidence interval must be within zero and one");
        }
    }

    public double width() {
        return upper - lower;
    }
}
