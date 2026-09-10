package ais.app;

import ais.analysis.AnalysisEngine;
import ais.analysis.AnalysisEvent;
import ais.analysis.AnalysisFilter;
import ais.analysis.AnalysisRunSummary;
import ais.analysis.AnalysisSnapshot;
import ais.analysis.DefaultAnalysisEngine;
import ais.analysis.VesselMetadataUpdatedEvent;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.NormalizedAisEvent;
import ais.domain.ReceiverProfile;
import ais.domain.SourceMode;
import ais.domain.VesselMetadataUpdate;
import ais.input.AisInputPipeline;
import ais.input.InputDiagnostic;
import ais.input.InputDiagnosticCode;
import ais.input.MessageSource;
import ais.input.PipelineResult;
import ais.input.ReceivedNmea;
import ais.input.SourceListener;
import ais.input.SourceReference;
import ais.input.live.UdpAisSource;
import ais.input.live.UdpSourceConfig;
import ais.storage.AggregateWriteQueue;
import ais.storage.AnalysisExclusionPeriod;
import ais.storage.AnalysisResultStore;
import ais.storage.AnalysisRun;
import ais.storage.DiagnosticSummary;
import ais.storage.VesselMetadataObservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class LiveAnalysisService implements AutoCloseable {

    public static final int DEFAULT_QUEUE_CAPACITY = 8_192;
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(1);

    private final ReceiverProfile receiver;
    private final AnalysisProfile profile;
    private final UdpSourceConfig config;
    private final AnalysisResultStore resultStore;
    private final Supplier<AnalysisEngine> engineFactory;
    private final Supplier<MessageSource> sourceFactory;
    private final Clock clock;
    private final ArrayBlockingQueue<ReceivedNmea> queue;
    private final ExecutorService analysisExecutor;
    private final ScheduledExecutorService snapshotTicker;
    private final AggregateWriteQueue databaseWrites =
            new AggregateWriteQueue();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean tickScheduled = new AtomicBoolean();
    private final Object overflowLock = new Object();
    private final ArrayDeque<Instant> recentRecords = new ArrayDeque<>();
    private final Map<String, MutableDiagnostic> diagnostics =
            new LinkedHashMap<>();
    private final List<AnalysisExclusionPeriod> exclusionPeriods =
            new ArrayList<>();
    private final List<VesselMetadataObservation> metadata =
            new ArrayList<>();

    private volatile LiveState state = LiveState.IDLE;
    private volatile LiveFrame latestFrame;
    private volatile FrameListener listener = ignored -> { };
    private volatile MessageSource source;
    private volatile CompletableFuture<Void> sourceStopped;
    private volatile CompletableFuture<LiveFrame> activationFuture;
    private volatile CompletableFuture<LiveFrame> stoppingFuture;
    private volatile boolean closed;
    private volatile boolean runPersisted;
    private AnalysisEngine engine;
    private AisInputPipeline pipeline;
    private AnalysisRun run;
    private AnalysisFilter filter = AnalysisFilter.all();
    private final AtomicLong receivedCount = new AtomicLong();
    private long decodedCount;
    private boolean overflowPending;
    private long overflowCount;
    private long firstDroppedSequence;
    private Instant overflowStartedAt;
    private String persistentWarning = "";
    private Instant lastSnapshotAt;
    private Instant nextCheckpointAt;

    public LiveAnalysisService(
            ReceiverProfile receiver,
            AnalysisProfile profile,
            UdpSourceConfig config,
            AnalysisResultStore resultStore) {
        this(receiver, profile, config, resultStore,
                DefaultAnalysisEngine::new,
                () -> new UdpAisSource(config),
                Clock.systemUTC(), DEFAULT_QUEUE_CAPACITY);
    }

    LiveAnalysisService(
            ReceiverProfile receiver,
            AnalysisProfile profile,
            UdpSourceConfig config,
            AnalysisResultStore resultStore,
            Supplier<AnalysisEngine> engineFactory,
            Supplier<MessageSource> sourceFactory,
            Clock clock,
            int queueCapacity) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.config = Objects.requireNonNull(config, "config");
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore");
        this.engineFactory = Objects.requireNonNull(engineFactory,
                "engineFactory");
        this.sourceFactory = Objects.requireNonNull(sourceFactory,
                "sourceFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        queue = new ArrayBlockingQueue<>(queueCapacity);
        analysisExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ais-live-analysis");
            thread.setDaemon(true);
            return thread;
        });
        snapshotTicker = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "ais-live-snapshot-ticker");
                    thread.setDaemon(true);
                    return thread;
                });
        snapshotTicker.scheduleAtFixedRate(this::scheduleRefresh,
                1, 1, TimeUnit.SECONDS);
        latestFrame = frame(null, "");
        activationFuture = CompletableFuture.completedFuture(latestFrame);
    }

    public synchronized CompletableFuture<LiveFrame> startOrResume() {
        requireOpen();
        if (state == LiveState.STOPPED) {
            return resume();
        }
        if (state != LiveState.IDLE) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            state == LiveState.ERROR
                                    ? "エラー時点までの結果をリセット操作で確定してから再試行してください"
                                    : "リアルタイム受信はすでに動作中です"));
        }
        state = LiveState.STARTING;
        emitState("UDP受信を開始しています…");
        CompletableFuture<LiveFrame> activation = submit(() -> {
            initializeNewSession();
            return run;
        }).thenCompose(created -> databaseWrites.submit(() -> {
            resultStore.beginLiveRun(created);
            runPersisted = true;
            return null;
        })).thenCompose(ignored -> submit(() -> {
            if (state == LiveState.STOPPING) {
                return emit(snapshotSafe(), "受信開始を取り消しました");
            }
            startSource();
            return frame(snapshot(clock.instant()), "UDP待受を開始しました");
        })).exceptionally(failure -> fail(failure));
        activationFuture = activation;
        stoppingFuture = null;
        return activation;
    }

    public synchronized CompletableFuture<LiveFrame> stop() {
        if (state == LiveState.STOPPING && stoppingFuture != null) {
            return stoppingFuture;
        }
        if (state != LiveState.RUNNING && state != LiveState.STARTING) {
            return CompletableFuture.completedFuture(latestFrame);
        }
        state = LiveState.STOPPING;
        emitState("受信停止とSQLite保存を行っています…");
        CompletableFuture<LiveFrame> activation = activationFuture;
        CompletableFuture<LiveFrame> stopping = activation
                .handle((ignored, failure) -> null)
                .thenCompose(ignored -> requestSourceStop())
                .thenCompose(ignored -> finalizeCurrentRun())
                .thenCompose(summary -> submit(() -> {
                    state = LiveState.STOPPED;
                    return emit(snapshot(summary.endedAt()),
                            "受信を停止し、集計をSQLiteへ保存しました");
                })).exceptionally(this::fail);
        stoppingFuture = stopping;
        return stopping;
    }

    public synchronized CompletableFuture<LiveFrame> reset() {
        if (state != LiveState.STOPPED && state != LiveState.ERROR) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "stop realtime reception before resetting"));
        }
        if (state == LiveState.ERROR && run != null && runPersisted) {
            state = LiveState.STOPPING;
            emitState("エラー発生時点までの集計を確定しています…");
            return requestSourceStop()
                    .thenCompose(ignored -> finalizeCurrentRun())
                    .thenCompose(ignored -> clearForReset())
                    .exceptionally(this::fail);
        }
        return clearForReset();
    }

    private CompletableFuture<LiveFrame> clearForReset() {
        return submit(() -> {
            clearSession();
            state = LiveState.IDLE;
            return emit(null, "集計表示をリセットしました");
        });
    }

    public CompletableFuture<LiveFrame> setFilter(AnalysisFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return submit(() -> {
            this.filter = filter;
            return emit(snapshotSafe(), "");
        });
    }

    public LiveFrame currentFrame() {
        return latestFrame;
    }

    public LiveState state() {
        return state;
    }

    public boolean isReceiving() {
        return state == LiveState.STARTING
                || state == LiveState.RUNNING
                || state == LiveState.STOPPING;
    }

    public String endpointLabel() {
        String address = config.bindAddress() == null
                ? "全インターフェース"
                : config.bindAddress().getHostAddress();
        return "UDP " + address + ":" + config.port();
    }

    public void setFrameListener(FrameListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    private CompletableFuture<LiveFrame> resume() {
        state = LiveState.STARTING;
        emitState("UDP受信を再開しています…");
        CompletableFuture<LiveFrame> activation = databaseWrites.submit(() -> {
            resultStore.reopenLiveRun(run.id());
            return null;
        }).thenCompose(ignored -> submit(() -> {
            if (state == LiveState.STOPPING) {
                return emit(snapshotSafe(), "受信再開を取り消しました");
            }
            pipeline.reset();
            engine.resetIntervalCursors(clock.instant());
            startSource();
            return emit(snapshotSafe(), "UDP待受を再開しました");
        })).exceptionally(this::fail);
        activationFuture = activation;
        stoppingFuture = null;
        return activation;
    }

    private void initializeNewSession() {
        clearSession();
        Instant started = clock.instant();
        AnalysisRunId runId = AnalysisRunId.create();
        engine = engineFactory.get();
        engine.begin(new AnalysisContext(receiver, profile, SourceMode.LIVE,
                runId, started));
        pipeline = new AisInputPipeline();
        run = new AnalysisRun(runId, SourceMode.LIVE, null,
                endpointLabel(), null, receiver.id(), profile.id(), started);
        nextCheckpointAt = nextFiveMinuteBoundary(started);
    }

    private void startSource() {
        sourceStopped = new CompletableFuture<>();
        source = sourceFactory.get();
        source.start(new SourceListener() {
            @Override
            public void onStarted() {
                submit(() -> {
                    if (state == LiveState.STARTING) {
                        state = LiveState.RUNNING;
                        return emit(snapshotSafe(), "受信中");
                    }
                    if (state == LiveState.STOPPING && source != null) {
                        source.stop();
                    }
                    return latestFrame;
                });
            }

            @Override
            public void onRecord(ReceivedNmea record) {
                receivedCount.incrementAndGet();
                if (!queue.offer(record)) {
                    noteOverflow(record);
                    return;
                }
                scheduleDrain();
            }

            @Override
            public void onDiagnostic(InputDiagnostic diagnostic) {
                submit(() -> {
                    recordDiagnostic(diagnostic);
                    return null;
                });
            }

            @Override
            public void onCompleted() {
                sourceStopped.complete(null);
            }

            @Override
            public void onFailure(Throwable error) {
                sourceStopped.completeExceptionally(error);
                submit(() -> {
                    fail(error);
                    return null;
                });
            }
        });
    }

    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            analysisExecutor.execute(() -> {
                try {
                    drainQueue();
                } catch (Throwable failure) {
                    fail(failure);
                } finally {
                    drainScheduled.set(false);
                    if (!queue.isEmpty()) {
                        scheduleDrain();
                    }
                }
            });
        }
    }

    private void scheduleRefresh() {
        if (state != LiveState.RUNNING
                || !tickScheduled.compareAndSet(false, true)) {
            return;
        }
        analysisExecutor.execute(() -> {
            try {
                if (state != LiveState.RUNNING || engine == null) {
                    return;
                }
                Instant now = clock.instant();
                if (nextCheckpointAt != null
                        && !now.isBefore(nextCheckpointAt)) {
                    checkpoint(now);
                    nextCheckpointAt = nextFiveMinuteBoundary(now);
                }
                emit(snapshot(now), "");
            } catch (Throwable failure) {
                fail(failure);
            } finally {
                tickScheduled.set(false);
            }
        });
    }

    private void drainQueue() {
        ReceivedNmea record;
        while ((record = queue.poll()) != null) {
            handleOverflow(takeOverflowBefore(record), record.receivedAt());
            accept(record);
        }
    }

    private CompletableFuture<Void> requestSourceStop() {
        MessageSource activeSource = source;
        CompletableFuture<Void> stopped = sourceStopped;
        if (activeSource != null) {
            activeSource.stop();
        }
        return stopped == null
                ? CompletableFuture.completedFuture(null)
                : stopped.handle((ignored, failure) -> null);
    }

    private CompletableFuture<AnalysisRunSummary> finalizeCurrentRun() {
        return submit(() -> {
            drainQueue();
            handleOverflow(takePendingOverflow(), clock.instant());
            finishPipeline();
            Instant now = clock.instant();
            engine.resetIntervalCursors(now);
            return engine.checkpoint(now);
        }).thenCompose(summary -> databaseWrites.submit(() -> {
            resultStore.checkpointLiveRun(run, summary,
                    diagnosticSummaries(summary),
                    List.copyOf(exclusionPeriods),
                    List.copyOf(metadata), true);
            return summary;
        }));
    }

    private void noteOverflow(ReceivedNmea record) {
        synchronized (overflowLock) {
            if (!overflowPending) {
                overflowPending = true;
                overflowStartedAt = record.receivedAt();
                firstDroppedSequence = record.sequence();
            }
            overflowCount = Math.addExact(overflowCount, 1);
        }
    }

    private OverflowEpisode takeOverflowBefore(ReceivedNmea record) {
        synchronized (overflowLock) {
            if (!overflowPending
                    || record.sequence() <= firstDroppedSequence) {
                return null;
            }
            return takePendingOverflowLocked();
        }
    }

    private OverflowEpisode takePendingOverflow() {
        synchronized (overflowLock) {
            return overflowPending ? takePendingOverflowLocked() : null;
        }
    }

    private OverflowEpisode takePendingOverflowLocked() {
        OverflowEpisode episode = new OverflowEpisode(
                overflowStartedAt, overflowCount);
        overflowPending = false;
        overflowStartedAt = null;
        overflowCount = 0;
        firstDroppedSequence = 0;
        return episode;
    }

    private void handleOverflow(OverflowEpisode episode, Instant resumedAt) {
        if (episode == null) {
            return;
        }
        Instant start = episode.startedAt();
        Instant end = resumedAt.isBefore(start) ? start : resumedAt;
        engine.resetIntervalCursors(end);
        exclusionPeriods.add(new AnalysisExclusionPeriod(
                start, end, "SOURCE_OVERFLOW", episode.droppedCount()));
        recordDiagnostic(InputDiagnosticCode.SOURCE_OVERFLOW, end,
                episode.droppedCount());
        persistentWarning = "PC処理遅延により入力を完全に処理できませんでした。"
                + "影響区間をAIS欠落率から除外します。";
    }

    private void accept(ReceivedNmea record) {
        recentRecords.addLast(record.receivedAt());
        trimRateWindow(record.receivedAt());
        PipelineResult result = pipeline.accept(record);
        result.diagnostics().forEach(this::recordDiagnostic);
        for (NormalizedAisEvent event : result.events()) {
            decodedCount++;
            List<AnalysisEvent> analysisEvents = engine.accept(event);
            if (event instanceof VesselMetadataUpdate update) {
                analysisEvents.stream()
                        .filter(VesselMetadataUpdatedEvent.class::isInstance)
                        .map(VesselMetadataUpdatedEvent.class::cast)
                        .findFirst()
                        .ifPresent(updated -> metadata.add(
                                new VesselMetadataObservation(
                                        updated.metadata(),
                                        update.messageType())));
            }
        }
        if (!record.receivedAt().isBefore(nextCheckpointAt)) {
            checkpoint(record.receivedAt());
            nextCheckpointAt = nextFiveMinuteBoundary(record.receivedAt());
        }
        if (lastSnapshotAt == null
                || !record.receivedAt().isBefore(
                        lastSnapshotAt.plus(SNAPSHOT_INTERVAL))) {
            lastSnapshotAt = record.receivedAt();
            emit(snapshot(record.receivedAt()), "");
        }
    }

    private void checkpoint(Instant at) {
        AnalysisRunSummary summary = engine.checkpoint(at);
        List<DiagnosticSummary> diagnosticCopy =
                diagnosticSummaries(summary);
        List<AnalysisExclusionPeriod> exclusionCopy =
                List.copyOf(exclusionPeriods);
        List<VesselMetadataObservation> metadataCopy = List.copyOf(metadata);
        databaseWrites.submit(() -> {
            resultStore.checkpointLiveRun(run, summary, diagnosticCopy,
                    exclusionCopy, metadataCopy, false);
            return null;
        }).exceptionally(failure -> {
            submit(() -> {
                fail(failure);
                return null;
            });
            return null;
        });
    }

    private void finishPipeline() {
        if (pipeline == null) {
            return;
        }
        PipelineResult remaining = pipeline.finish(clock.instant());
        remaining.diagnostics().forEach(this::recordDiagnostic);
        pipeline.reset();
    }

    private AnalysisSnapshot snapshot(Instant at) {
        return engine == null ? null : engine.snapshot(at, filter);
    }

    private AnalysisSnapshot snapshotSafe() {
        return engine == null || run == null ? null : snapshot(clock.instant());
    }

    private LiveFrame emit(AnalysisSnapshot snapshot, String message) {
        LiveFrame value = frame(snapshot, message);
        latestFrame = value;
        listener.onFrame(value);
        return value;
    }

    private LiveFrame emitState(String message) {
        LiveFrame previous = latestFrame;
        LiveFrame value = new LiveFrame(state, previous.snapshot(),
                run == null ? previous.sessionStartedAt() : run.startedAt(),
                clock.instant(), receivedCount.get(), decodedCount,
                previous.diagnosticCount(),
                previous.duplicateCount(),
                previous.receivedRecordsPerSecond(), endpointLabel(),
                effectiveMessage(message));
        latestFrame = value;
        listener.onFrame(value);
        return value;
    }

    private LiveFrame frame(AnalysisSnapshot snapshot, String message) {
        Instant now = clock.instant();
        trimRateWindow(now);
        return new LiveFrame(state, snapshot,
                run == null ? null : run.startedAt(), now,
                receivedCount.get(), decodedCount, diagnosticTotal(),
                duplicateTotal(),
                recentRecords.size(), endpointLabel(),
                effectiveMessage(message));
    }

    private LiveFrame fail(Throwable failure) {
        state = LiveState.ERROR;
        MessageSource active = source;
        if (active != null && (active.status() == ais.input.SourceStatus.STARTING
                || active.status() == ais.input.SourceStatus.RUNNING)) {
            active.stop();
        }
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return emit(snapshotSafe(), "エラー: "
                + (cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    private void recordDiagnostic(InputDiagnostic diagnostic) {
        recordDiagnostic(diagnostic.code(), diagnostic.occurredAt(), 1);
    }

    private void recordDiagnostic(InputDiagnosticCode code, Instant at,
                                  long count) {
        diagnostics.computeIfAbsent(code.name(),
                        ignored -> new MutableDiagnostic())
                .accept(at, count);
    }

    private List<DiagnosticSummary> diagnosticSummaries(
            AnalysisRunSummary summary) {
        Map<String, MutableDiagnostic> combined =
                new LinkedHashMap<>(diagnostics);
        summary.excludedIntervalCounts().forEach((reason, count) ->
                combined.put("INTERVAL_" + reason.name(),
                        new MutableDiagnostic(count, null, null)));
        return combined.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new DiagnosticSummary(
                        entry.getKey(), entry.getValue().count,
                        entry.getValue().first, entry.getValue().last))
                .toList();
    }

    private long diagnosticTotal() {
        return diagnostics.values().stream()
                .mapToLong(value -> value.count).sum();
    }

    private long duplicateTotal() {
        MutableDiagnostic duplicate = diagnostics.get(
                InputDiagnosticCode.DUPLICATE.name());
        return duplicate == null ? 0 : duplicate.count;
    }

    private void trimRateWindow(Instant now) {
        Instant threshold = now.minusSeconds(1);
        while (!recentRecords.isEmpty()
                && recentRecords.peekFirst().isBefore(threshold)) {
            recentRecords.removeFirst();
        }
    }

    private static Instant nextFiveMinuteBoundary(Instant instant) {
        ZonedDateTime local = instant.atZone(JAPAN)
                .truncatedTo(ChronoUnit.MINUTES);
        int remainder = local.getMinute() % 5;
        return local.plusMinutes(remainder == 0 ? 5 : 5 - remainder)
                .toInstant();
    }

    private void clearSession() {
        queue.clear();
        recentRecords.clear();
        diagnostics.clear();
        exclusionPeriods.clear();
        metadata.clear();
        engine = null;
        pipeline = null;
        run = null;
        runPersisted = false;
        source = null;
        sourceStopped = null;
        receivedCount.set(0);
        decodedCount = 0;
        synchronized (overflowLock) {
            overflowPending = false;
            overflowCount = 0;
            firstDroppedSequence = 0;
            overflowStartedAt = null;
        }
        persistentWarning = "";
        lastSnapshotAt = null;
        nextCheckpointAt = null;
    }

    private <T> CompletableFuture<T> submit(ThrowingSupplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }, analysisExecutor);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("live service is closed");
        }
    }

    private String effectiveMessage(String message) {
        if (persistentWarning.isBlank()) {
            return message;
        }
        return message == null || message.isBlank()
                ? persistentWarning
                : persistentWarning + " / " + message;
    }

    @Override
    public void close() {
        closed = true;
        if (isReceiving()) {
            try {
                stop().join();
            } catch (RuntimeException ignored) {
                // The UI has already reported the underlying failure.
            }
        } else if (state == LiveState.ERROR && run != null && runPersisted) {
            try {
                requestSourceStop()
                        .thenCompose(ignored -> finalizeCurrentRun()).join();
            } catch (RuntimeException ignored) {
                // A storage failure cannot be recovered during shutdown.
            }
        }
        snapshotTicker.shutdownNow();
        analysisExecutor.shutdown();
        databaseWrites.close();
    }

    @FunctionalInterface
    public interface FrameListener {
        void onFrame(LiveFrame frame);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class MutableDiagnostic {
        private long count;
        private Instant first;
        private Instant last;

        private MutableDiagnostic() {
        }

        private MutableDiagnostic(long count, Instant first, Instant last) {
            this.count = count;
            this.first = first;
            this.last = last;
        }

        private void accept(Instant at, long increment) {
            count = Math.addExact(count, increment);
            if (first == null || at.isBefore(first)) {
                first = at;
            }
            if (last == null || at.isAfter(last)) {
                last = at;
            }
        }
    }

    private record OverflowEpisode(Instant startedAt, long droppedCount) {
    }
}
