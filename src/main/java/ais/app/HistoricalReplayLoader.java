package ais.app;

import ais.domain.NormalizedAisEvent;
import ais.input.AisInputPipeline;
import ais.input.InputDiagnostic;
import ais.input.PipelineResult;
import ais.input.ReceivedNmea;
import ais.input.SourceListener;
import ais.input.history.HistoricalDaySelection;
import ais.input.history.HistoricalRecord;
import ais.input.history.InputFingerprint;
import ais.input.history.InputFingerprintCalculator;
import ais.input.history.ReplayTimeline;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class HistoricalReplayLoader {

    private final ZoneId sourceZone;

    public HistoricalReplayLoader(ZoneId sourceZone) {
        this.sourceZone = Objects.requireNonNull(sourceZone, "sourceZone");
    }

    public HistoricalReplayDataset load(
            HistoricalDaySelection selection,
            ProgressListener progress) throws IOException {
        Objects.requireNonNull(selection, "selection");
        ProgressListener listener = progress == null
                ? ignored -> { } : progress;
        List<NormalizedAisEvent> events = new ArrayList<>();
        List<InputDiagnostic> diagnostics = new ArrayList<>();
        AisInputPipeline pipeline = new AisInputPipeline();
        long records = 0;
        Instant lastRecordTime = selection.date()
                .atStartOfDay(sourceZone).toInstant();

        SourceListener timelineListener = new SourceListener() {
            @Override
            public void onRecord(ReceivedNmea record) {
            }

            @Override
            public void onDiagnostic(InputDiagnostic diagnostic) {
                diagnostics.add(diagnostic);
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onFailure(Throwable error) {
            }
        };

        try (ReplayTimeline timeline = new ReplayTimeline(
                selection, sourceZone, timelineListener)) {
            while (timeline.hasNext()) {
                HistoricalRecord record = timeline.next();
                lastRecordTime = record.receivedAt();
                PipelineResult result = pipeline.accept(new ReceivedNmea(
                        record.receivedAt(), records, record.sentence(),
                        record.source()));
                events.addAll(result.events());
                diagnostics.addAll(result.diagnostics());
                records++;
                if (records % 10_000 == 0) {
                    listener.onProgress(records);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("historical replay loading cancelled");
                }
            }
        }
        diagnostics.addAll(pipeline.finish(lastRecordTime).diagnostics());
        events.sort(Comparator
                .comparing(NormalizedAisEvent::receivedAt)
                .thenComparingLong(NormalizedAisEvent::sequence));
        InputFingerprint fingerprint = new InputFingerprintCalculator()
                .calculate(selection.files());
        Instant start = events.isEmpty()
                ? null : events.getFirst().receivedAt();
        Instant end = events.isEmpty()
                ? null : events.getLast().receivedAt();
        listener.onProgress(records);
        return new HistoricalReplayDataset(
                selection, fingerprint, events, diagnostics,
                records, start, end);
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long processedRecords);
    }
}
