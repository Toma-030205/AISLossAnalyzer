package ais.analysis;

public final class LossEstimator {

    private LossEstimator() {
    }

    public static long estimateMissingMessages(
            double actualSeconds,
            double expectedSeconds) {
        if (!Double.isFinite(actualSeconds)
                || !Double.isFinite(expectedSeconds)
                || actualSeconds < 0.0
                || expectedSeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "intervals must be finite; actualSeconds >= 0 and "
                            + "expectedSeconds > 0");
        }

        long estimatedTransmissions =
                Math.round(actualSeconds / expectedSeconds);
        return Math.max(0L, estimatedTransmissions - 1L);
    }
}
