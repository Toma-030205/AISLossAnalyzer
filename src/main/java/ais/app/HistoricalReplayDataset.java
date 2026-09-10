package ais.app;

import ais.domain.NormalizedAisEvent;
import ais.input.InputDiagnostic;
import ais.input.history.HistoricalDaySelection;
import ais.input.history.InputFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record HistoricalReplayDataset(
        HistoricalDaySelection selection,
        InputFingerprint fingerprint,
        List<NormalizedAisEvent> events,
        List<InputDiagnostic> diagnostics,
        long inputRecordCount,
        Instant startTime,
        Instant endTime) {

    public HistoricalReplayDataset {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(fingerprint, "fingerprint");
        events = List.copyOf(events);
        diagnostics = List.copyOf(diagnostics);
        if (inputRecordCount < 0) {
            throw new IllegalArgumentException(
                    "inputRecordCount must not be negative");
        }
        if (events.isEmpty()) {
            if (startTime != null || endTime != null) {
                throw new IllegalArgumentException(
                        "empty replay must not have a time range");
            }
        } else if (startTime == null || endTime == null
                || endTime.isBefore(startTime)) {
            throw new IllegalArgumentException(
                    "non-empty replay requires an ordered time range");
        }
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    public String logicalInputName() {
        return selection.files().stream()
                .map(path -> path.getFileName().toString())
                .reduce((left, right) -> left + ";" + right)
                .orElse("historical-input");
    }
}
