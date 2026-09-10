package ais.ui;

import java.awt.Color;
import java.util.List;

public final class HeatmapColorScale {

    private static final List<Double> UPPER_BOUNDS = List.of(
            1.0, 5.0, 10.0, 20.0, 30.0,
            50.0, 70.0, 90.0, 100.0);
    private static final List<Color> COLORS = List.of(
            new Color(0, 88, 45, 138),
            new Color(35, 139, 69, 138),
            new Color(120, 198, 121, 138),
            new Color(255, 237, 120, 142),
            new Color(254, 196, 79, 142),
            new Color(254, 141, 60, 145),
            new Color(244, 91, 39, 148),
            new Color(215, 48, 39, 150),
            new Color(128, 0, 38, 155));

    public Color colorFor(double percent) {
        double value = Math.max(0.0, Math.min(100.0, percent));
        for (int index = 0; index < UPPER_BOUNDS.size(); index++) {
            if (value <= UPPER_BOUNDS.get(index)) {
                return COLORS.get(index);
            }
        }
        return COLORS.getLast();
    }

    public List<Double> upperBounds() {
        return UPPER_BOUNDS;
    }

    public List<Color> colors() {
        return COLORS;
    }
}
