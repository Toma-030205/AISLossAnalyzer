package ais.ui.viewmodel;

import ais.domain.ReceiverProfile;
import ais.spatial.GridCellId;

import java.time.Instant;
import java.util.List;

public record MapViewModel(
        Instant displayTime,
        ReceiverProfile receiver,
        List<VesselMapItem> vessels,
        List<GridMapItem> gridCells,
        VesselDetailViewModel selectedVessel,
        GridDetailViewModel selectedGrid,
        Integer selectedMmsi,
        GridCellId selectedCell,
        HeatmapMetric metric,
        double freshnessMultiplier,
        String analysisRulesVersion,
        String vesselClassLabel,
        int gridSizeMeters,
        double gridOriginEasting,
        double gridOriginNorthing,
        long processedEventCount,
        long diagnosticCount) {

    public MapViewModel {
        vessels = List.copyOf(vessels);
        gridCells = List.copyOf(gridCells);
        if (analysisRulesVersion == null || analysisRulesVersion.isBlank()
                || vesselClassLabel == null || vesselClassLabel.isBlank()) {
            throw new IllegalArgumentException(
                    "map export labels must not be blank");
        }
        if (gridSizeMeters <= 0) {
            throw new IllegalArgumentException(
                    "gridSizeMeters must be greater than zero");
        }
    }
}
