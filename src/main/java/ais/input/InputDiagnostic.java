package ais.input;

import java.time.Instant;
import java.util.Objects;

public record InputDiagnostic(
        Instant occurredAt,
        InputDiagnosticCode code,
        SourceReference source,
        String detail) {

    public InputDiagnostic {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(source, "source");
        detail = detail == null ? "" : detail.trim();
    }
}
