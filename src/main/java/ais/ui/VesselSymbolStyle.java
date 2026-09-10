package ais.ui;

import ais.domain.FreshnessState;
import ais.domain.VesselClass;

import java.awt.Color;

public final class VesselSymbolStyle {

    public Color fill(FreshnessState state) {
        return switch (state) {
            case NORMAL -> new Color(45, 166, 91);
            case CAUTION -> new Color(245, 201, 45);
            case VIOLATION -> new Color(214, 61, 54);
            case UNKNOWN -> new Color(135, 142, 147);
        };
    }

    public Color classOutline(VesselClass vesselClass) {
        return switch (vesselClass) {
            case CLASS_A -> new Color(28, 105, 190);
            case CLASS_B -> new Color(226, 126, 34);
            case UNKNOWN -> new Color(100, 100, 100);
        };
    }
}
