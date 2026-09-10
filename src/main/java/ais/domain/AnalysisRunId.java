package ais.domain;

import java.util.Objects;
import java.util.UUID;

public record AnalysisRunId(UUID value) {

    public AnalysisRunId {
        Objects.requireNonNull(value, "value");
    }

    public static AnalysisRunId create() {
        return new AnalysisRunId(UUID.randomUUID());
    }

    public static AnalysisRunId parse(String value) {
        return new AnalysisRunId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
