package ais.logic;

public final class AisAnalysisRules {

    public static final int CLASS_A_POSITION = 1;
    public static final int STATIC_VOYAGE = 5;
    public static final int CLASS_B_POSITION = 18;

    private static final double DEFAULT_TRACK_GAP_MULTIPLIER =
            10.0;

    private static final double TRACK_GAP_MULTIPLIER =
            readTrackGapMultiplier();

    private AisAnalysisRules() {
    }

    public static int getAnalysisType(int messageType) {

        if (messageType == 1
                || messageType == 2
                || messageType == 3) {

            return CLASS_A_POSITION;
        }

        if (messageType == 5) {
            return STATIC_VOYAGE;
        }

        if (messageType == 18) {
            return CLASS_B_POSITION;
        }

        return -1;
    }

    public static boolean belongsToAnalysisType(
            int messageType,
            int analysisType) {

        return getAnalysisType(messageType) == analysisType;
    }

    public static long getTrackGapThresholdSeconds(
            int analysisType) {

        return Math.max(
                1L,
                Math.round(
                        getMaximumExpectedIntervalSeconds(analysisType)
                                * TRACK_GAP_MULTIPLIER));
    }

    public static double getTrackGapMultiplier() {
        return TRACK_GAP_MULTIPLIER;
    }

    private static long getMaximumExpectedIntervalSeconds(
            int analysisType) {

        if (analysisType == CLASS_A_POSITION
                || analysisType == CLASS_B_POSITION) {

            return 180L;
        }

        if (analysisType == STATIC_VOYAGE) {
            return 360L;
        }

        throw new IllegalArgumentException(
                "Unsupported analysis type: " + analysisType);
    }

    private static double readTrackGapMultiplier() {

        String configured =
                System.getProperty("ais.trackGapMultiplier");

        if (configured == null) {
            return DEFAULT_TRACK_GAP_MULTIPLIER;
        }

        double multiplier;

        try {
            multiplier = Double.parseDouble(configured);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "ais.trackGapMultiplier must be a number: "
                            + configured,
                    e);
        }

        if (!Double.isFinite(multiplier) || multiplier <= 0) {
            throw new IllegalArgumentException(
                    "ais.trackGapMultiplier must be greater than 0: "
                            + configured);
        }

        return multiplier;
    }
}
