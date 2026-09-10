package ais.spatial;

public record DistanceBand(
        int index,
        double lowerKilometers,
        double upperKilometers) {

    public DistanceBand {
        if (index < 0
                || !Double.isFinite(lowerKilometers)
                || !Double.isFinite(upperKilometers)
                || lowerKilometers < 0.0
                || upperKilometers <= lowerKilometers) {
            throw new IllegalArgumentException("invalid distance band");
        }
    }

    public String label() {
        return format(lowerKilometers) + "-" + format(upperKilometers)
                + " km";
    }

    private static String format(double value) {
        return value == Math.rint(value)
                ? Long.toString(Math.round(value))
                : Double.toString(value);
    }
}
