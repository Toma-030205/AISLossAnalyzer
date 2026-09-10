package ais.nmea;

import ais.input.InputDiagnostic;
import ais.input.InputDiagnosticCode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AisFragmentAssembler {

    public static final Duration DEFAULT_FRAGMENT_TIMEOUT =
            Duration.ofSeconds(30);

    private final Duration timeout;
    private final Map<FragmentKey, FragmentBuffer> buffers =
            new HashMap<>();

    public AisFragmentAssembler() {
        this(DEFAULT_FRAGMENT_TIMEOUT);
    }

    public AisFragmentAssembler(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "fragment timeout must be greater than zero");
        }
    }

    public FragmentAssemblyResult accept(NmeaFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");

        List<InputDiagnostic> diagnostics =
                expireBuffers(fragment.receivedAt());

        if (fragment.totalFragments() == 1) {
            return new FragmentAssemblyResult(
                    List.of(toCompleted(fragment, fragment.payload(),
                            fragment.fillBits())),
                    diagnostics);
        }

        FragmentKey key = FragmentKey.from(fragment);

        FragmentBuffer existing = buffers.get(key);
        if (fragment.fragmentNumber() == 1
                && existing != null
                && existing.containsFragment(1)
                && !existing.containsSamePayload(fragment)) {
            FragmentBuffer displaced = buffers.remove(key);
            diagnostics.add(incompleteDiagnostic(
                    fragment.receivedAt(),
                    displaced,
                    "a new first fragment replaced an incomplete message"));
        }

        FragmentBuffer buffer = buffers.computeIfAbsent(
                key,
                ignored -> new FragmentBuffer(fragment));
        FragmentBuffer.AddResult addResult = buffer.add(fragment);

        if (addResult == FragmentBuffer.AddResult.DUPLICATE_CONFLICT) {
            buffers.remove(key);
            diagnostics.add(new InputDiagnostic(
                    fragment.receivedAt(),
                    InputDiagnosticCode.INVALID_FRAGMENT,
                    fragment.source(),
                    "the same fragment number contained conflicting payloads"));
            return new FragmentAssemblyResult(List.of(), diagnostics);
        }
        if (!buffer.isComplete()) {
            return new FragmentAssemblyResult(List.of(), diagnostics);
        }

        buffers.remove(key);
        CompletedAisPayload completed = toCompleted(
                fragment,
                buffer.combinedPayload(),
                buffer.finalFillBits());
        return new FragmentAssemblyResult(List.of(completed), diagnostics);
    }

    public List<InputDiagnostic> flushIncomplete(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        List<InputDiagnostic> diagnostics = new ArrayList<>();

        for (FragmentBuffer buffer : buffers.values()) {
            diagnostics.add(incompleteDiagnostic(
                    occurredAt,
                    buffer,
                    "the source ended before every fragment arrived"));
        }
        buffers.clear();
        return List.copyOf(diagnostics);
    }

    public void clear() {
        buffers.clear();
    }

    private List<InputDiagnostic> expireBuffers(Instant now) {
        List<InputDiagnostic> diagnostics = new ArrayList<>();
        Iterator<Map.Entry<FragmentKey, FragmentBuffer>> iterator =
                buffers.entrySet().iterator();

        while (iterator.hasNext()) {
            FragmentBuffer buffer = iterator.next().getValue();
            Duration age = Duration.between(buffer.lastUpdatedAt(), now);

            if (!age.isNegative() && age.compareTo(timeout) > 0) {
                diagnostics.add(incompleteDiagnostic(
                        now,
                        buffer,
                        "fragment assembly timed out"));
                iterator.remove();
            }
        }
        return diagnostics;
    }

    private static CompletedAisPayload toCompleted(
            NmeaFragment finalFragment,
            String payload,
            int fillBits) {
        return new CompletedAisPayload(
                finalFragment.receivedAt(),
                finalFragment.sequence(),
                finalFragment.source(),
                finalFragment.formatter(),
                finalFragment.radioChannel(),
                payload,
                fillBits);
    }

    private static InputDiagnostic incompleteDiagnostic(
            Instant occurredAt,
            FragmentBuffer buffer,
            String detail) {
        return new InputDiagnostic(
                occurredAt,
                InputDiagnosticCode.INCOMPLETE_FRAGMENT,
                buffer.firstSource(),
                detail);
    }

    private record FragmentKey(
            String sourceId,
            String formatter,
            String sequentialMessageId,
            String radioChannel,
            int totalFragments) {

        static FragmentKey from(NmeaFragment fragment) {
            return new FragmentKey(
                    fragment.source().sourceId(),
                    fragment.formatter(),
                    fragment.sequentialMessageId(),
                    fragment.radioChannel(),
                    fragment.totalFragments());
        }
    }
}
