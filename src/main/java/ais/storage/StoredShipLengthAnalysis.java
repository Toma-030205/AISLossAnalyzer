package ais.storage;

import java.util.List;
import java.util.Objects;

public record StoredShipLengthAnalysis(
        StoredShipLengthCoverage coverage,
        List<StoredShipLengthCell> cells) {

    public StoredShipLengthAnalysis {
        Objects.requireNonNull(coverage, "coverage");
        cells = List.copyOf(cells);
    }
}
