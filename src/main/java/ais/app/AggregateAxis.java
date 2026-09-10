package ais.app;

public enum AggregateAxis {
    DISTANCE_BAND("距離帯別"),
    HOUR_OF_DAY("時間帯別");

    private final String label;

    AggregateAxis(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
