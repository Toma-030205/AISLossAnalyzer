package ais.app;

import java.time.LocalDate;
import java.util.Objects;

public record HistoricalBatchFailure(LocalDate date, String message) {

    public HistoricalBatchFailure {
        Objects.requireNonNull(date, "date");
        message = Objects.requireNonNullElse(message, "原因不明").trim();
        if (message.isEmpty()) {
            message = "原因不明";
        }
    }
}
