package ais.decode;

import ais.domain.NormalizedAisEvent;

import java.util.Objects;

public record DecodedAisEnvelope(
        NormalizedAisEvent event,
        String completePayload,
        int fillBits) {

    public DecodedAisEnvelope {
        Objects.requireNonNull(event, "event");
        if (completePayload == null || completePayload.isEmpty()) {
            throw new IllegalArgumentException(
                    "completePayload must not be empty");
        }
        if (fillBits < 0 || fillBits > 5) {
            throw new IllegalArgumentException("fillBits must be 0 to 5");
        }
    }
}
