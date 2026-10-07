package ais.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record DailyDataQualityRow(
        LocalDate date,
        DailyDataQualityState state,
        String inputName,
        int inputFileCount,
        long inputUncompressedBytes,
        Instant firstAggregateBucket,
        Instant lastAggregateBucket,
        int aggregateBucketCount,
        int distinctVesselCount,
        long decodedAisEventCount,
        long acceptedIntervalCount,
        long estimatedMissingCount,
        long duplicateCount,
        long inputAnomalyCount,
        long invalidPositionCount,
        long thirtyMinuteGapCount,
        long distanceJumpCount,
        long outsideDistanceRangeCount,
        String note) {

    public static final int EXPECTED_BUCKETS_PER_DAY = 288;

    public DailyDataQualityRow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(state, "state");
        inputName = inputName == null ? "" : inputName;
        note = note == null ? "" : note;
        if (inputFileCount < 0 || inputUncompressedBytes < 0
                || aggregateBucketCount < 0 || distinctVesselCount < 0
                || decodedAisEventCount < 0 || acceptedIntervalCount < 0
                || estimatedMissingCount < 0 || duplicateCount < 0
                || inputAnomalyCount < 0 || invalidPositionCount < 0
                || thirtyMinuteGapCount < 0 || distanceJumpCount < 0
                || outsideDistanceRangeCount < 0) {
            throw new IllegalArgumentException(
                    "daily data quality counts must not be negative");
        }
    }

    public boolean analyzed() {
        return state != DailyDataQualityState.NOT_ANALYZED;
    }
}
