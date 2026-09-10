package ais.input.history;

import ais.domain.SourceMode;
import ais.input.MessageSource;
import ais.input.ReceivedNmea;
import ais.input.SourceListener;
import ais.input.SourceStatus;

import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class HistoricalLogSource implements MessageSource {

    private final HistoricalDaySelection selection;
    private final ZoneId sourceZone;
    private final AtomicReference<SourceStatus> status =
            new AtomicReference<>(SourceStatus.IDLE);

    private volatile boolean stopRequested;
    private volatile Thread worker;

    public HistoricalLogSource(
            HistoricalDaySelection selection,
            ZoneId sourceZone) {
        this.selection = Objects.requireNonNull(selection, "selection");
        this.sourceZone = Objects.requireNonNull(sourceZone, "sourceZone");
    }

    @Override
    public SourceMode mode() {
        return SourceMode.HISTORICAL;
    }

    @Override
    public SourceStatus status() {
        return status.get();
    }

    @Override
    public synchronized void start(SourceListener listener) {
        Objects.requireNonNull(listener, "listener");
        SourceStatus current = status.get();
        if (current == SourceStatus.STARTING
                || current == SourceStatus.RUNNING
                || current == SourceStatus.STOPPING) {
            throw new IllegalStateException(
                    "historical source is already active");
        }

        stopRequested = false;
        status.set(SourceStatus.STARTING);
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("ais-history-source")
                .start(() -> runSource(listener));
    }

    @Override
    public synchronized void stop() {
        SourceStatus current = status.get();
        if (current != SourceStatus.STARTING
                && current != SourceStatus.RUNNING) {
            return;
        }

        stopRequested = true;
        status.set(SourceStatus.STOPPING);
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
    }

    private void runSource(SourceListener listener) {
        try (ReplayTimeline timeline = new ReplayTimeline(
                selection,
                sourceZone,
                listener)) {
            if (stopRequested) {
                status.set(SourceStatus.STOPPED);
                return;
            }
            status.set(SourceStatus.RUNNING);
            long sequence = 0;

            while (!stopRequested && timeline.hasNext()) {
                HistoricalRecord record = timeline.next();
                listener.onRecord(new ReceivedNmea(
                        record.receivedAt(),
                        sequence++,
                        record.sentence(),
                        record.source()));
            }

            if (stopRequested) {
                status.set(SourceStatus.STOPPED);
            } else {
                status.set(SourceStatus.COMPLETED);
                listener.onCompleted();
            }
        } catch (Throwable error) {
            if (stopRequested) {
                status.set(SourceStatus.STOPPED);
            } else {
                status.set(SourceStatus.FAILED);
                listener.onFailure(error);
            }
        } finally {
            worker = null;
        }
    }
}
