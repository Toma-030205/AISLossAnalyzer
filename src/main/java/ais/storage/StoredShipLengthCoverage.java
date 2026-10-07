package ais.storage;

public record StoredShipLengthCoverage(
        int analysisRunCount,
        int totalDistinctVesselCount,
        int knownLengthDistinctVesselCount,
        long totalVesselDayCount,
        long knownLengthVesselDayCount) {

    public StoredShipLengthCoverage {
        if (analysisRunCount < 0 || totalDistinctVesselCount < 0
                || knownLengthDistinctVesselCount < 0
                || knownLengthDistinctVesselCount > totalDistinctVesselCount
                || totalVesselDayCount < 0 || knownLengthVesselDayCount < 0
                || knownLengthVesselDayCount > totalVesselDayCount) {
            throw new IllegalArgumentException(
                    "invalid stored ship-length coverage");
        }
    }
}
