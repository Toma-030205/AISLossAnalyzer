package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;

import java.util.List;
import java.util.Objects;

public record ShipLengthPerformanceResult(
        ShipLengthPerformanceRequest request,
        ReceiverProfile receiverProfile,
        AnalysisProfile analysisProfile,
        int readyAnalysisRunCount,
        int reanalysisRequiredRunCount,
        int totalDistinctVesselCount,
        int knownLengthDistinctVesselCount,
        List<ShipLengthPerformanceRow> rows) {

    public ShipLengthPerformanceResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiverProfile, "receiverProfile");
        Objects.requireNonNull(analysisProfile, "analysisProfile");
        rows = List.copyOf(rows);
        if (readyAnalysisRunCount < 0 || reanalysisRequiredRunCount < 0
                || totalDistinctVesselCount < 0
                || knownLengthDistinctVesselCount < 0
                || knownLengthDistinctVesselCount
                > totalDistinctVesselCount) {
            throw new IllegalArgumentException(
                    "invalid ship-length performance totals");
        }
    }

    public double knownLengthCoveragePercent() {
        return totalDistinctVesselCount == 0 ? 0.0
                : knownLengthDistinctVesselCount * 100.0
                / totalDistinctVesselCount;
    }
}
