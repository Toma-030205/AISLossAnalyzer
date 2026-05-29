package ais.stats;

public class DistanceBinStatistics {

    public final int binStartKm;

    public final int binEndKm;

    public int observed;

    public long loss;

    public double distanceSum;

    public int distanceCount;

    public DistanceBinStatistics(
            int binStartKm,
            int binEndKm) {

        this.binStartKm = binStartKm;
        this.binEndKm = binEndKm;
    }

    public void add(
            double distance,
            long lossCount) {

        observed++;
        loss += lossCount;
        distanceSum += distance;
        distanceCount++;
    }

    public long getExpected() {

        return observed + loss;
    }

    public double getLossRate() {

        long expected =
                getExpected();

        if (expected == 0) {
            return 0;
        }

        return ((double) loss / expected) * 100.0;
    }

    public double getAverageDistance() {

        if (distanceCount == 0) {
            return 0;
        }

        return distanceSum / distanceCount;
    }

    public String getBinLabel() {

        return binStartKm + "-" + binEndKm + "km";
    }
}
