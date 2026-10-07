package ais.simulation.validation;

import ais.simulation.calibration.CommunicationModelId;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record ValidationRequest(
        CommunicationModelId modelId,
        LocalDate startDate,
        LocalDate endDate,
        Set<LocalDate> excludedDates,
        int iterationCount,
        long seedBase,
        boolean sensitivity) {

    public ValidationRequest {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        excludedDates = Set.copyOf(excludedDates);
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "検証終了日は開始日以降にしてください");
        }
        if (iterationCount < 1 || iterationCount > 1000) {
            throw new IllegalArgumentException(
                    "反復回数は1～1000にしてください");
        }
        for (LocalDate date : excludedDates) {
            if (date.isBefore(startDate) || date.isAfter(endDate)) {
                throw new IllegalArgumentException(
                        "除外日は検証期間内にしてください: " + date);
            }
        }
    }

    public int includedDayCount() {
        int days = 0;
        for (LocalDate date = startDate;
                !date.isAfter(endDate); date = date.plusDays(1)) {
            if (!excludedDates.contains(date)) {
                days++;
            }
        }
        return days;
    }
}
