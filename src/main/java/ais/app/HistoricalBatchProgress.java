package ais.app;

import java.time.LocalDate;
import java.util.Objects;

public record HistoricalBatchProgress(
        LocalDate date,
        Stage stage,
        int dayNumber,
        int totalDays,
        int savedDays,
        int skippedDays,
        int failedDays,
        long processedRecords) {

    public HistoricalBatchProgress {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(stage, "stage");
        if (dayNumber < 1 || totalDays < dayNumber) {
            throw new IllegalArgumentException(
                    "dayNumber must be between 1 and totalDays");
        }
        if (savedDays < 0 || skippedDays < 0 || failedDays < 0
                || processedRecords < 0) {
            throw new IllegalArgumentException(
                    "batch progress counts must not be negative");
        }
    }

    public enum Stage {
        FINGERPRINTING("保存済み確認中"),
        LOADING("読込中"),
        ANALYZING("解析中"),
        SAVING("保存中"),
        SAVED("保存完了"),
        SKIPPED("保存済みスキップ"),
        FAILED("失敗");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
