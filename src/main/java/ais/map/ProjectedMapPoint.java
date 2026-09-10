package ais.map;

public record ProjectedMapPoint(double x, double y) {

    public ProjectedMapPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException(
                    "map point must contain finite coordinates");
        }
    }
}
