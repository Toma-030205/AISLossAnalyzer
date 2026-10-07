package ais.ui.viewmodel;

import java.util.List;
import java.util.Objects;

public record SimulationOverlayViewModel(
        List<SimulationTruthMapItem> truthVessels,
        SimulationVesselDetailViewModel selectedVessel,
        String modelLabel,
        long seed) {

    public SimulationOverlayViewModel {
        Objects.requireNonNull(truthVessels, "truthVessels");
        Objects.requireNonNull(modelLabel, "modelLabel");
        truthVessels = List.copyOf(truthVessels);
    }
}
