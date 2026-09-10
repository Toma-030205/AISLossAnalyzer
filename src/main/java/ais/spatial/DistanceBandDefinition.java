package ais.spatial;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DistanceBandDefinition {

    private static final double BOUNDARY_EPSILON = 1.0e-9;

    private final double widthKilometers;
    private final double maximumKilometers;
    private final List<DistanceBand> bands;

    public DistanceBandDefinition(
            double widthKilometers,
            double maximumKilometers) {
        if (!Double.isFinite(widthKilometers)
                || !Double.isFinite(maximumKilometers)
                || widthKilometers <= 0.0
                || maximumKilometers <= 0.0
                || maximumKilometers % widthKilometers != 0.0) {
            throw new IllegalArgumentException(
                    "distance range must contain complete positive bands");
        }
        this.widthKilometers = widthKilometers;
        this.maximumKilometers = maximumKilometers;

        List<DistanceBand> values = new ArrayList<>();
        int bandCount = (int) Math.round(
                maximumKilometers / widthKilometers);
        for (int index = 0; index < bandCount; index++) {
            values.add(new DistanceBand(
                    index,
                    index * widthKilometers,
                    (index + 1) * widthKilometers));
        }
        bands = List.copyOf(values);
    }

    public Optional<DistanceBand> bandFor(double distanceKilometers) {
        if (!Double.isFinite(distanceKilometers)
                || distanceKilometers < 0.0
                || distanceKilometers > maximumKilometers
                + BOUNDARY_EPSILON) {
            return Optional.empty();
        }
        int index = distanceKilometers >= maximumKilometers
                ? bands.size() - 1
                : (int) Math.floor(distanceKilometers / widthKilometers);
        return Optional.of(bands.get(index));
    }

    public List<DistanceBand> bands() {
        return bands;
    }

    public double widthKilometers() {
        return widthKilometers;
    }

    public double maximumKilometers() {
        return maximumKilometers;
    }
}
