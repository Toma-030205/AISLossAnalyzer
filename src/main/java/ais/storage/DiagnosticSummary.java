package ais.storage;

import java.time.Instant;

public record DiagnosticSummary(
        String code,
        long count,
        Instant firstOccurredAt,
        Instant lastOccurredAt) {

    public DiagnosticSummary {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        code = code.trim();
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        if ((firstOccurredAt == null) != (lastOccurredAt == null)) {
            throw new IllegalArgumentException(
                    "diagnostic times must both be present or absent");
        }
        if (firstOccurredAt != null
                && firstOccurredAt.isAfter(lastOccurredAt)) {
            throw new IllegalArgumentException(
                    "first diagnostic time must not be after last time");
        }
    }
}
