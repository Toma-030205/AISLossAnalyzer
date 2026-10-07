package ais.simulation.validation;

import java.util.Objects;
import java.util.UUID;

public record SimulationExperimentId(String value) {

    public SimulationExperimentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("experiment id is blank");
        }
    }

    public static SimulationExperimentId create() {
        return new SimulationExperimentId(UUID.randomUUID().toString());
    }

    public static SimulationExperimentId parse(String value) {
        return new SimulationExperimentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
