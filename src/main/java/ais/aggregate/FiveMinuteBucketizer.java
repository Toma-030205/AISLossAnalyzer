package ais.aggregate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FiveMinuteBucketizer {

    public static final int BUCKET_MINUTES = 5;

    private final ZoneId zone;

    public FiveMinuteBucketizer(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public Instant bucketStart(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        ZonedDateTime local = instant.atZone(zone);
        int minute = local.getMinute()
                - local.getMinute() % BUCKET_MINUTES;
        return local.withMinute(minute)
                .withSecond(0)
                .withNano(0)
                .toInstant();
    }

    public List<TimeAllocation> split(Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(
                    "time allocation end must not be before its start");
        }
        if (end.equals(start)) {
            return List.of();
        }

        List<TimeAllocation> result = new ArrayList<>();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant bucket = bucketStart(cursor);
            Instant nextBoundary = bucket.atZone(zone)
                    .plusMinutes(BUCKET_MINUTES)
                    .toInstant();
            Instant sliceEnd = nextBoundary.isBefore(end)
                    ? nextBoundary
                    : end;
            result.add(new TimeAllocation(bucket, cursor, sliceEnd));
            cursor = sliceEnd;
        }
        return List.copyOf(result);
    }

    public ZoneId zone() {
        return zone;
    }
}
