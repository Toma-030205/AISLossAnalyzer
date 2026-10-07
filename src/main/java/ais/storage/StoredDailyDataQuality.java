package ais.storage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record StoredDailyDataQuality(
        LocalDate date,
        String inputName,
        int inputFileCount,
        long inputUncompressedBytes,
        Instant firstAggregateBucket,
        Instant lastAggregateBucket,
        int aggregateBucketCount,
        int distinctVesselCount,
        long acceptedIntervalCount,
        long estimatedMissingCount,
        long metadataUpdateCount,
        long intervalEventCount,
        long duplicateCount,
        long inputAnomalyCount,
        long invalidPositionCount,
        long thirtyMinuteGapCount,
        long distanceJumpCount,
        long sourceFailureCount,
        long outsideDistanceRangeCount) {

    public StoredDailyDataQuality {
        Objects.requireNonNull(date, "date");
        if (inputName == null || inputName.isBlank()) {
            throw new IllegalArgumentException("inputName must not be blank");
        }
        if (inputFileCount < 0 || inputUncompressedBytes < 0
                || aggregateBucketCount < 0 || distinctVesselCount < 0
                || acceptedIntervalCount < 0 || estimatedMissingCount < 0
                || metadataUpdateCount < 0 || intervalEventCount < 0
                || duplicateCount < 0 || inputAnomalyCount < 0
                || invalidPositionCount < 0 || thirtyMinuteGapCount < 0
                || distanceJumpCount < 0 || sourceFailureCount < 0
                || outsideDistanceRangeCount < 0) {
            throw new IllegalArgumentException(
                    "daily data quality counts must not be negative");
        }
        if ((firstAggregateBucket == null) != (lastAggregateBucket == null)) {
            throw new IllegalArgumentException(
                    "aggregate bucket bounds must both be present or absent");
        }
    }
}
