package ais.app;

import java.util.Arrays;
import java.util.List;

public enum ShipLengthBand {
    UNDER_50(0, "50m未満", true),
    FROM_50_TO_99(1, "50-99m", true),
    FROM_100_TO_149(2, "100-149m", true),
    FROM_150_TO_199(3, "150-199m", true),
    FROM_200_TO_249(4, "200-249m", true),
    AT_LEAST_250(5, "250m以上", true),
    UNKNOWN_OR_IMPLAUSIBLE(6, "不明・範囲外", false);

    private final int order;
    private final String label;
    private final boolean usableForTrend;

    ShipLengthBand(int order, String label, boolean usableForTrend) {
        this.order = order;
        this.label = label;
        this.usableForTrend = usableForTrend;
    }

    public int order() {
        return order;
    }

    public boolean usableForTrend() {
        return usableForTrend;
    }

    public static ShipLengthBand fromOrder(int order) {
        return Arrays.stream(values())
                .filter(value -> value.order == order)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown ship-length band order: " + order));
    }

    public static List<ShipLengthBand> trendBands() {
        return Arrays.stream(values())
                .filter(ShipLengthBand::usableForTrend)
                .toList();
    }

    @Override
    public String toString() {
        return label;
    }
}
