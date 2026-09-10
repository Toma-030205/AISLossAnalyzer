package ais.domain;

public record GeoPosition(double latitude, double longitude) {

    public GeoPosition {
        if (!Double.isFinite(latitude)
                || latitude < -90.0
                || latitude > 90.0) {
            throw new IllegalArgumentException(
                    "latitude must be finite and between -90 and 90: "
                            + latitude);
        }

        if (!Double.isFinite(longitude)
                || longitude < -180.0
                || longitude > 180.0) {
            throw new IllegalArgumentException(
                    "longitude must be finite and between -180 and 180: "
                            + longitude);
        }
    }

    public boolean isZeroPosition() {
        return latitude == 0.0 && longitude == 0.0;
    }
}
