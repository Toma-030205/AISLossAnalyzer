package ais.stats;

import java.util.ArrayList;
import java.util.List;

public class VesselStatisticsResult {

    public int mmsi;

    public int totalMessages;

    public long totalEstimatedLoss;

    public double lossRate;

    public long maxDelta;

    public double averageDelta;

    public double maxDistance;

    public Integer shipLength;

    // 欠落イベント時距離
    public List<Double> lossDistances =
            new ArrayList<>();

    // そのイベントの欠落数
    public List<Long> lossCounts =
            new ArrayList<>();

    @Override
public String toString() {

    return "MMSI=" + mmsi
            + " TOTAL_MESSAGES=" + totalMessages
            + " TOTAL_LOSS=" + totalEstimatedLoss
            + " LOSS_RATE="
            + String.format("%.2f", lossRate)
            + "%"
            + " AVG_DELTA="
            + String.format("%.1f", averageDelta)
            + " MAX_DELTA="
            + maxDelta
            + " MAX_DISTANCE="
            + String.format("%.1f", maxDistance)
            + "km";
}
}