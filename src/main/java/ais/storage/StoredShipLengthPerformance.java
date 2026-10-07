package ais.storage;

import java.util.List;

public record StoredShipLengthPerformance(
        int readyAnalysisRunCount,
        int reanalysisRequiredRunCount,
        int totalDistinctVesselCount,
        int knownLengthDistinctVesselCount,
        List<StoredShipLengthPerformanceRow> rows) {

    public StoredShipLengthPerformance {
        rows = List.copyOf(rows);
        if (readyAnalysisRunCount < 0 || reanalysisRequiredRunCount < 0
                || totalDistinctVesselCount < 0
                || knownLengthDistinctVesselCount < 0
                || knownLengthDistinctVesselCount
                > totalDistinctVesselCount) {
            throw new IllegalArgumentException("invalid performance totals");
        }
    }
}
