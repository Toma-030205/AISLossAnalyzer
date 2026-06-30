package ais.logic;

public final class LossEstimator {

    private LossEstimator() {
    }

    public static long estimateMissingMessages(
            double actualSeconds,
            double expectedSeconds) {

        if (!Double.isFinite(actualSeconds)
                || !Double.isFinite(expectedSeconds)
                || actualSeconds < 0
                || expectedSeconds <= 0) {

            throw new IllegalArgumentException(
                    "Intervals must be finite; actualSeconds >= 0 and "
                            + "expectedSeconds > 0");
        }

        long estimatedTransmissions =
                Math.round(actualSeconds / expectedSeconds);

        return Math.max(0, estimatedTransmissions - 1);
    }
}
