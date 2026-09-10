package ais.input;

import java.time.Instant;
import java.util.Objects;

public record ReceivedNmea(
        Instant receivedAt,
        long sequence,
        String sentence,
        SourceReference source) {

    public ReceivedNmea {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(source, "source");
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "sequence must be zero or greater");
        }
        if (sentence == null || sentence.isBlank()) {
            throw new IllegalArgumentException("sentence must not be blank");
        }
        sentence = sentence.trim();
    }
}
