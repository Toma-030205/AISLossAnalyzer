package ais.app;

import ais.domain.VesselClass;

import java.util.Objects;

public record ShipLengthAnalysisRow(
        ShipLengthBand shipLengthBand,
        VesselClass vesselClass,
        int distinctVesselCount,
        long vesselDayCount,
        double averageDailyMaximumLowerKilometers,
        String medianDailyMaximumDistanceBand,
        long atLeastThirtyKilometerVesselDays,
        double atLeastThirtyKilometerRatePercent,
        long atLeastFiftyKilometerVesselDays,
        double atLeastFiftyKilometerRatePercent,
        boolean sufficientData) {

    public ShipLengthAnalysisRow {
        Objects.requireNonNull(shipLengthBand, "shipLengthBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        if (medianDailyMaximumDistanceBand == null
                || medianDailyMaximumDistanceBand.isBlank()) {
            throw new IllegalArgumentException(
                    "median distance band must not be blank");
        }
        if (distinctVesselCount < 0 || vesselDayCount <= 0
                || !Double.isFinite(averageDailyMaximumLowerKilometers)
                || averageDailyMaximumLowerKilometers < 0.0
                || atLeastThirtyKilometerVesselDays < 0
                || atLeastThirtyKilometerVesselDays > vesselDayCount
                || !validPercent(atLeastThirtyKilometerRatePercent)
                || atLeastFiftyKilometerVesselDays < 0
                || atLeastFiftyKilometerVesselDays > vesselDayCount
                || !validPercent(atLeastFiftyKilometerRatePercent)) {
            throw new IllegalArgumentException(
                    "invalid ship-length analysis row");
        }
    }

    private static boolean validPercent(double value) {
        return Double.isFinite(value)
                && value >= 0.0 && value <= 100.0 + 1.0e-9;
    }
}
