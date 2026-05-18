package ais.stats;

public class VesselStatisticsResult {

    public int mmsi;

    public int totalMessages;

    public long totalEstimatedLoss;

    public double lossRate;

    public long maxDelta;

    public double averageDelta;

    public double averageDistance;

    public double maxDistance;

    @Override
    public String toString() {

        return "MMSI=" + mmsi
                + " TOTAL_MESSAGES=" + totalMessages
                + " TOTAL_LOSS=" + totalEstimatedLoss
                + " LOSS_RATE=" + String.format("%.2f", lossRate)
                + "%"
                + " AVG_DELTA=" + String.format("%.1f", averageDelta)
                + " MAX_DELTA=" + maxDelta;
                + " AVG_DISTANCE="+ String.format("%.1f", averageDistance)+ "km"
                + " MAX_DISTANCE="+ String.format("%.1f", maxDistance)+ "km"
    }
}