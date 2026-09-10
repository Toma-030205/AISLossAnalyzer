package ais.domain;

import java.time.Duration;
import java.util.Objects;

public record AnalysisProfile(
        AnalysisProfileId id,
        String rulesVersion,
        double freshnessMultiplier,
        int gridSizeMeters,
        double gridOriginEasting,
        double gridOriginNorthing,
        int distanceBinKilometers,
        int maximumDistanceKilometers,
        int minimumExpectedCount,
        int minimumDistinctVessels,
        Duration trackGapThreshold,
        double maximumDistanceJumpKilometers,
        double classBCsHighSpeedIntervalSeconds) {

    public AnalysisProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(trackGapThreshold, "trackGapThreshold");

        if (rulesVersion == null || rulesVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "rulesVersion must not be blank");
        }
        rulesVersion = rulesVersion.trim();

        if (!Double.isFinite(freshnessMultiplier)
                || freshnessMultiplier <= 0.0) {
            throw new IllegalArgumentException(
                    "freshnessMultiplier must be greater than zero");
        }
        requirePositive(gridSizeMeters, "gridSizeMeters");
        if (!Double.isFinite(gridOriginEasting)
                || !Double.isFinite(gridOriginNorthing)) {
            throw new IllegalArgumentException(
                    "grid origin must contain finite UTM coordinates");
        }
        requirePositive(distanceBinKilometers, "distanceBinKilometers");
        requirePositive(maximumDistanceKilometers,
                "maximumDistanceKilometers");
        requirePositive(minimumExpectedCount, "minimumExpectedCount");
        requirePositive(minimumDistinctVessels,
                "minimumDistinctVessels");

        if (trackGapThreshold.isZero()
                || trackGapThreshold.isNegative()) {
            throw new IllegalArgumentException(
                    "trackGapThreshold must be greater than zero");
        }
        if (!Double.isFinite(maximumDistanceJumpKilometers)
                || maximumDistanceJumpKilometers <= 0.0) {
            throw new IllegalArgumentException(
                    "maximumDistanceJumpKilometers must be greater than zero");
        }
        if (!Double.isFinite(classBCsHighSpeedIntervalSeconds)
                || classBCsHighSpeedIntervalSeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "classBCsHighSpeedIntervalSeconds must be greater than zero");
        }
    }

    public static AnalysisProfile phaseOneDefaults() {
        return new AnalysisProfile(
                new AnalysisProfileId("phase-1-default"),
                "phase-1-v1",
                3.0,
                2_000,
                0.0,
                0.0,
                5,
                70,
                30,
                3,
                Duration.ofMinutes(30),
                30.0,
                30.0);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    name + " must be greater than zero: " + value);
        }
    }
}
