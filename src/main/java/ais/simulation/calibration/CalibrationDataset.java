package ais.simulation.calibration;

import ais.domain.AnalysisRunId;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record CalibrationDataset(
        List<CalibrationDayRow> dailyRows,
        List<AnalysisRunId> sourceRunIds,
        List<LocalDate> missingDates) {

    public CalibrationDataset {
        Objects.requireNonNull(dailyRows, "dailyRows");
        Objects.requireNonNull(sourceRunIds, "sourceRunIds");
        Objects.requireNonNull(missingDates, "missingDates");
        dailyRows = List.copyOf(dailyRows);
        sourceRunIds = List.copyOf(sourceRunIds);
        missingDates = List.copyOf(missingDates);
    }
}
