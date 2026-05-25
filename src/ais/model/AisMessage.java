package ais.model;

import java.time.LocalDateTime;

public class AisMessage {

    // 共通
    public int messageType;
    public int mmsi;
    public String bits;

    // 時刻
    public LocalDateTime timestamp;

    // Dynamic data (Type 1,2,3)
    public Double lat = null;
    public Double lon = null;
    public Double sog = null;
    public Double cog = null;
    public Double trueHeading = null;
    public Integer navStatus = null;

    // Static data (Type 5)
    public Integer imo = null;
    public String callSign = null;
    public String vesselName = null;
    public Integer shipType = null;
    public String destination = null;
    public Integer shipLength = null;

    @Override
    public String toString() {

        return "AisMessage{" +
                "timestamp='" + timestamp + '\'' +
                ", type=" + messageType +
                ", mmsi=" + mmsi +
                ", lat=" + lat +
                ", lon=" + lon +
                ", sog=" + sog +
                ", vesselName='" + vesselName + '\'' +
                ", shipLength=" + shipLength +
                '}';
    }
}