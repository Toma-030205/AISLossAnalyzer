package ais.domain;

import java.time.Instant;

public sealed interface NormalizedAisEvent
        permits PositionReport, VesselMetadataUpdate {

    Instant receivedAt();

    long sequence();

    int messageType();

    int mmsi();

    VesselClass vesselClass();
}
