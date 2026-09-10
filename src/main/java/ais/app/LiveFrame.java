package ais.app;

import ais.analysis.AnalysisSnapshot;

import java.time.Instant;

public record LiveFrame(
        LiveState state,
        AnalysisSnapshot snapshot,
        Instant sessionStartedAt,
        Instant displayTime,
        long receivedRecordCount,
        long decodedEventCount,
        long diagnosticCount,
        long duplicateCount,
        double receivedRecordsPerSecond,
        String endpoint,
        String message) {

    public LiveFrame {
        message = message == null ? "" : message;
        endpoint = endpoint == null ? "" : endpoint;
        if (receivedRecordCount < 0 || decodedEventCount < 0
                || diagnosticCount < 0 || duplicateCount < 0
                || duplicateCount > diagnosticCount
                || !Double.isFinite(receivedRecordsPerSecond)
                || receivedRecordsPerSecond < 0.0) {
            throw new IllegalArgumentException("invalid live counters");
        }
    }

    public long errorDiagnosticCount() {
        return diagnosticCount - duplicateCount;
    }
}
