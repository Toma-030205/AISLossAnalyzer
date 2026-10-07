package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;

import java.util.List;
import java.util.Objects;

public record ShipLengthAnalysisResult(
        ShipLengthAnalysisRequest request,
        ReceiverProfile receiverProfile,
        AnalysisProfile analysisProfile,
        int analysisRunCount,
        int totalDistinctVesselCount,
        int knownLengthDistinctVesselCount,
        long totalVesselDayCount,
        long knownLengthVesselDayCount,
        List<ShipLengthAnalysisRow> rows,
        List<ShipLengthDistanceCell> cells) {

    public ShipLengthAnalysisResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiverProfile, "receiverProfile");
        Objects.requireNonNull(analysisProfile, "analysisProfile");
        rows = List.copyOf(rows);
        cells = List.copyOf(cells);
        if (analysisRunCount < 0 || totalDistinctVesselCount < 0
                || knownLengthDistinctVesselCount < 0
                || knownLengthDistinctVesselCount > totalDistinctVesselCount
                || totalVesselDayCount < 0 || knownLengthVesselDayCount < 0
                || knownLengthVesselDayCount > totalVesselDayCount) {
            throw new IllegalArgumentException(
                    "invalid ship-length analysis totals");
        }
    }

    public double knownLengthCoveragePercent() {
        return totalDistinctVesselCount == 0 ? 0.0
                : knownLengthDistinctVesselCount * 100.0
                / totalDistinctVesselCount;
    }

    public double knownLengthVesselDayCoveragePercent() {
        return totalVesselDayCount == 0 ? 0.0
                : knownLengthVesselDayCount * 100.0
                / totalVesselDayCount;
    }
}
