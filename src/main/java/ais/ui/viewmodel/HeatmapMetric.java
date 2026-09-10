package ais.ui.viewmodel;

public enum HeatmapMetric {
    FRESHNESS_VIOLATION("情報鮮度違反率"),
    ESTIMATED_LOSS("推定欠落率");

    private final String label;

    HeatmapMetric(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
