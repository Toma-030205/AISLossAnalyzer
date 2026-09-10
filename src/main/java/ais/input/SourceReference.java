package ais.input;

import ais.domain.SourceMode;

import java.nio.file.Path;
import java.util.Objects;

public record SourceReference(
        SourceMode mode,
        String sourceId,
        String detail,
        long recordNumber) {

    public SourceReference {
        Objects.requireNonNull(mode, "mode");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        sourceId = sourceId.trim();
        detail = detail == null ? "" : detail.trim();
        if (recordNumber < 0) {
            throw new IllegalArgumentException(
                    "recordNumber must be zero or greater");
        }
    }

    public static SourceReference historical(Path file, long lineNumber) {
        Objects.requireNonNull(file, "file");
        Path absolute = file.toAbsolutePath().normalize();
        return new SourceReference(
                SourceMode.HISTORICAL,
                absolute.toString(),
                absolute.getFileName().toString(),
                lineNumber);
    }

    public static SourceReference live(String endpoint, long packetNumber) {
        return new SourceReference(
                SourceMode.LIVE,
                Objects.requireNonNull(endpoint, "endpoint"),
                endpoint,
                packetNumber);
    }
}
