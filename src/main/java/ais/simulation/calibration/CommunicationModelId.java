package ais.simulation.calibration;

import java.util.Objects;
import java.util.UUID;

public record CommunicationModelId(UUID value) {

    public CommunicationModelId {
        Objects.requireNonNull(value, "value");
    }

    public static CommunicationModelId create() {
        return new CommunicationModelId(UUID.randomUUID());
    }

    public static CommunicationModelId parse(String value) {
        return new CommunicationModelId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
