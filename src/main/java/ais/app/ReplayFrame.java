package ais.app;

import ais.analysis.AnalysisSnapshot;

import java.time.Instant;

public record ReplayFrame(
        ReplayState state,
        HistoricalReplayDataset dataset,
        AnalysisSnapshot snapshot,
        Instant displayTime,
        long processedEventCount,
        String message) {

    public ReplayFrame {
        message = message == null ? "" : message;
        if (processedEventCount < 0) {
            throw new IllegalArgumentException(
                    "processedEventCount must not be negative");
        }
    }
}
