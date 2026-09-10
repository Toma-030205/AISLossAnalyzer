package ais.nmea;

import ais.input.SourceReference;

import java.time.Instant;
import java.util.Objects;

public record CompletedAisPayload(
        Instant receivedAt,
        long sequence,
        SourceReference source,
        String formatter,
        String radioChannel,
        String payload,
        int fillBits) {

    public CompletedAisPayload {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(formatter, "formatter");
        Objects.requireNonNull(radioChannel, "radioChannel");
        Objects.requireNonNull(payload, "payload");
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (fillBits < 0 || fillBits > 5) {
            throw new IllegalArgumentException("fillBits must be 0 to 5");
        }
    }
}
