package ais.analysis;

import ais.domain.PositionReport;
import ais.domain.TrailPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class TrailBuffer {

    public static final Duration DEFAULT_RETENTION = Duration.ofMinutes(60);

    private final Duration retention;
    private final Deque<TrailPoint> points = new ArrayDeque<>();

    public TrailBuffer() {
        this(DEFAULT_RETENTION);
    }

    public TrailBuffer(Duration retention) {
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException(
                    "trail retention must be greater than zero");
        }
    }

    public void accept(PositionReport report) {
        Objects.requireNonNull(report, "report");
        if (!points.isEmpty()
                && report.receivedAt().isBefore(
                        points.peekLast().receivedAt())) {
            points.clear();
        }
        points.addLast(new TrailPoint(
                report.receivedAt(),
                report.position()));
        prune(report.receivedAt(), retention);
    }

    public List<TrailPoint> pointsSince(
            Instant displayTime,
            Duration requestedDuration) {
        Objects.requireNonNull(displayTime, "displayTime");
        Objects.requireNonNull(requestedDuration, "requestedDuration");
        if (requestedDuration.isNegative()
                || requestedDuration.compareTo(retention) > 0) {
            throw new IllegalArgumentException(
                    "requested trail duration is outside retention");
        }

        Instant earliest = displayTime.minus(requestedDuration);
        List<TrailPoint> result = new ArrayList<>();
        for (TrailPoint point : points) {
            if (!point.receivedAt().isBefore(earliest)
                    && !point.receivedAt().isAfter(displayTime)) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    public void clear() {
        points.clear();
    }

    private void prune(Instant now, Duration duration) {
        Instant earliest = now.minus(duration);
        while (!points.isEmpty()
                && points.peekFirst().receivedAt().isBefore(earliest)) {
            points.removeFirst();
        }
    }
}
