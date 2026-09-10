package ais.spatial;

public record ProjectedPoint(double easting, double northing) {

    public ProjectedPoint {
        if (!Double.isFinite(easting) || !Double.isFinite(northing)) {
            throw new IllegalArgumentException(
                    "projected coordinates must be finite");
        }
    }
}
