package ais.input.history;

import ais.input.SourceReference;

import java.time.Instant;
import java.util.Objects;

public record HistoricalRecord(
        Instant receivedAt,
        String sentence,
        SourceReference source) {

    public HistoricalRecord {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(source, "source");
        if (sentence == null || sentence.isBlank()) {
            throw new IllegalArgumentException("sentence must not be blank");
        }
        sentence = sentence.trim();
    }
}
