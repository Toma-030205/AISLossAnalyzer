package ais.app;

public enum DailyDataQualityState {
    ALL_BUCKETS_PRESENT("全枠あり"),
    PARTIAL("一部欠け"),
    REVIEW_REQUIRED("要確認"),
    NOT_ANALYZED("未解析");

    private final String label;

    DailyDataQualityState(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
