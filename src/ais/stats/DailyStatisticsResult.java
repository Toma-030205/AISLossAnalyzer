package ais.stats;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;

public class DailyStatisticsResult {

    public LocalDate date;

    public DayOfWeek dayOfWeek;

    public int messageType;

    public int mmsi;

    public Integer shipLength;

    private final VesselStatisticsResult result;

    public DailyStatisticsResult(
            LocalDate date,
            int messageType,
            int mmsi,
            Integer shipLength,
            VesselStatisticsResult result) {

        this.date = date;
        this.dayOfWeek = date.getDayOfWeek();
        this.messageType = messageType;
        this.mmsi = mmsi;
        this.shipLength = shipLength;
        this.result = result;
    }

    public int getTotalMessages() {

        return result.totalMessages;
    }

    public Collection<DistanceBinStatistics> getDistanceBins() {

        return result.getDistanceBins();
    }
}
