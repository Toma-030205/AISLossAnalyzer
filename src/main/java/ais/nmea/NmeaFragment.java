package ais.nmea;

import ais.input.SourceReference;

import java.time.Instant;
import java.util.Objects;

public record NmeaFragment(
        Instant receivedAt,
        long sequence,
        SourceReference source,
        String formatter,
        int totalFragments,
        int fragmentNumber,
        String sequentialMessageId,
        String radioChannel,
        String payload,
        int fillBits) {

    public NmeaFragment {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(formatter, "formatter");
        Objects.requireNonNull(payload, "payload");
        sequentialMessageId = sequentialMessageId == null
                ? ""
                : sequentialMessageId;
        radioChannel = radioChannel == null ? "" : radioChannel;
    }
}
