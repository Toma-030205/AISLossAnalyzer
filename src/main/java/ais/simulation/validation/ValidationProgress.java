package ais.simulation.validation;

import java.time.LocalDate;

public record ValidationProgress(
        int completedUnits,
        int totalUnits,
        LocalDate currentDate,
        int iteration,
        ValidationModelVariant variant,
        String message) {

    public ValidationProgress {
        if (completedUnits < 0 || totalUnits < 0
                || completedUnits > totalUnits || iteration < 0) {
            throw new IllegalArgumentException("invalid validation progress");
        }
        message = message == null ? "" : message;
    }

    public int percent() {
        return totalUnits == 0 ? 0
                : (int) Math.round(completedUnits * 100.0 / totalUnits);
    }
}
