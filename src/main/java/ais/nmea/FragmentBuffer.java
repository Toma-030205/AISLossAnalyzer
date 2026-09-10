package ais.nmea;

import ais.input.SourceReference;

import java.time.Instant;

final class FragmentBuffer {

    private final String[] payloadParts;
    private final SourceReference firstSource;
    private Instant lastUpdatedAt;
    private int receivedCount;
    private int finalFillBits;

    FragmentBuffer(NmeaFragment first) {
        payloadParts = new String[first.totalFragments()];
        firstSource = first.source();
        lastUpdatedAt = first.receivedAt();
    }

    AddResult add(NmeaFragment fragment) {
        int index = fragment.fragmentNumber() - 1;
        String existing = payloadParts[index];

        if (existing != null) {
            return existing.equals(fragment.payload())
                    ? AddResult.DUPLICATE_SAME
                    : AddResult.DUPLICATE_CONFLICT;
        }

        payloadParts[index] = fragment.payload();
        receivedCount++;
        lastUpdatedAt = fragment.receivedAt();

        if (fragment.fragmentNumber() == fragment.totalFragments()) {
            finalFillBits = fragment.fillBits();
        }
        return AddResult.ADDED;
    }

    boolean containsSamePayload(NmeaFragment fragment) {
        String existing = payloadParts[fragment.fragmentNumber() - 1];
        return existing != null && existing.equals(fragment.payload());
    }

    boolean containsFragment(int fragmentNumber) {
        return payloadParts[fragmentNumber - 1] != null;
    }

    boolean isComplete() {
        return receivedCount == payloadParts.length;
    }

    String combinedPayload() {
        StringBuilder result = new StringBuilder();
        for (String part : payloadParts) {
            if (part == null) {
                throw new IllegalStateException("fragment buffer is incomplete");
            }
            result.append(part);
        }
        return result.toString();
    }

    Instant lastUpdatedAt() {
        return lastUpdatedAt;
    }

    SourceReference firstSource() {
        return firstSource;
    }

    int finalFillBits() {
        return finalFillBits;
    }

    enum AddResult {
        ADDED,
        DUPLICATE_SAME,
        DUPLICATE_CONFLICT
    }
}
