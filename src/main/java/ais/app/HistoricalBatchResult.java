package ais.app;

import java.time.Duration;
import java.util.List;

public record HistoricalBatchResult(
        int totalDays,
        int savedDays,
        int skippedDays,
        List<HistoricalBatchFailure> failures,
        boolean cancelled,
        Duration elapsed) {

    public HistoricalBatchResult {
        failures = List.copyOf(failures);
        if (totalDays < 0 || savedDays < 0 || skippedDays < 0) {
            throw new IllegalArgumentException(
                    "batch result counts must not be negative");
        }
        if (savedDays + skippedDays + failures.size() > totalDays) {
            throw new IllegalArgumentException(
                    "processed day count exceeds totalDays");
        }
        if (elapsed == null || elapsed.isNegative()) {
            throw new IllegalArgumentException(
                    "elapsed must not be null or negative");
        }
    }

    public int failedDays() {
        return failures.size();
    }

    public int processedDays() {
        return savedDays + skippedDays + failedDays();
    }

    public int unprocessedDays() {
        return totalDays - processedDays();
    }
}
