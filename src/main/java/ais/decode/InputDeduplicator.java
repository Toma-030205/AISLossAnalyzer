package ais.decode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class InputDeduplicator {

    public static final Duration DEFAULT_DUPLICATE_WINDOW =
            Duration.ofSeconds(1);

    private final Duration duplicateWindow;
    private final Map<DeduplicationKey, Instant> lastSeen =
            new HashMap<>();
    private final Deque<SeenEntry> ageOrder = new ArrayDeque<>();

    private Instant latestInputTime;

    public InputDeduplicator() {
        this(DEFAULT_DUPLICATE_WINDOW);
    }

    public InputDeduplicator(Duration duplicateWindow) {
        this.duplicateWindow = Objects.requireNonNull(
                duplicateWindow,
                "duplicateWindow");
        if (duplicateWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "duplicate window must not be negative");
        }
    }

    public boolean isDuplicate(DecodedAisEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        Instant receivedAt = envelope.event().receivedAt();

        if (latestInputTime != null && receivedAt.isBefore(latestInputTime)) {
            clear();
        }
        latestInputTime = receivedAt;
        prune(receivedAt);

        DeduplicationKey key = new DeduplicationKey(
                envelope.event().mmsi(),
                envelope.event().messageType(),
                envelope.completePayload(),
                envelope.fillBits());
        Instant previous = lastSeen.get(key);
        boolean duplicate = previous != null
                && !receivedAt.isBefore(previous)
                && Duration.between(previous, receivedAt)
                .compareTo(duplicateWindow) <= 0;

        lastSeen.put(key, receivedAt);
        ageOrder.addLast(new SeenEntry(key, receivedAt));
        return duplicate;
    }

    public void clear() {
        lastSeen.clear();
        ageOrder.clear();
        latestInputTime = null;
    }

    private void prune(Instant currentTime) {
        while (!ageOrder.isEmpty()) {
            SeenEntry entry = ageOrder.peekFirst();
            Duration age = Duration.between(entry.seenAt(), currentTime);

            if (age.isNegative()
                    || age.compareTo(duplicateWindow) <= 0) {
                return;
            }

            ageOrder.removeFirst();
            if (entry.seenAt().equals(lastSeen.get(entry.key()))) {
                lastSeen.remove(entry.key());
            }
        }
    }

    private record DeduplicationKey(
            int mmsi,
            int messageType,
            String payload,
            int fillBits) {
    }

    private record SeenEntry(
            DeduplicationKey key,
            Instant seenAt) {
    }
}
