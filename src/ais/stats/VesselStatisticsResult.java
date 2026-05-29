package ais.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class VesselStatisticsResult {

    public int mmsi;

    public int totalMessages;

    public long totalEstimatedLoss;

    public double lossRate;

    public long maxDelta;

    public double averageDelta;

    public double maxDistance;

    public Integer shipLength;

    private static final int DISTANCE_BIN_KM = 10;

    private Map<Integer, DistanceBinStatistics> distanceBins =
            new TreeMap<>();

    // 欠落イベント時距離
    public List<Double> lossDistances =
            new ArrayList<>();

    // そのイベントの欠落数
    public List<Long> lossCounts =
            new ArrayList<>();

    public void addDistanceBin(
            double distance,
            long lossCount) {

        int binStart =
                ((int) Math.floor(distance / DISTANCE_BIN_KM))
                        * DISTANCE_BIN_KM;

        DistanceBinStatistics bin =
                distanceBins.get(binStart);

        if (bin == null) {

            bin =
                    new DistanceBinStatistics(
                            binStart,
                            binStart + DISTANCE_BIN_KM);

            distanceBins.put(
                    binStart,
                    bin);
        }

        bin.add(
                distance,
                lossCount);
    }

    public Collection<DistanceBinStatistics> getDistanceBins() {

        return distanceBins.values();
    }

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
