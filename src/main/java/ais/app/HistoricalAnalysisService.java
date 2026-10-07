package ais.app;

import ais.analysis.AnalysisEngine;
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
import ais.input.InputDiagnostic;
import ais.input.history.HistoricalDaySelection;
import ais.input.history.InputFingerprint;
import ais.input.history.InputFingerprintCalculator;
import ais.storage.AnalysisResultStore;
import ais.storage.AnalysisRun;
import ais.storage.DiagnosticSummary;
import ais.storage.VesselMetadataObservation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HistoricalAnalysisService implements AutoCloseable {

    private final Function<java.time.LocalDate, ReceiverProfile>
            receiverResolver;
    private final AnalysisProfile profile;
    private final HistoricalReplayLoader loader;
    private final AnalysisResultStore resultStore;
    private final Supplier<AnalysisEngine> engineFactory;
    private final ExecutorService analysisExecutor;
    private final AtomicBoolean batchRunning = new AtomicBoolean();
    private final AtomicBoolean batchCancellationRequested =
            new AtomicBoolean();

    private HistoricalReplayDataset dataset;
    private ReceiverProfile activeReceiver;
    private AnalysisEngine replayEngine;
    private AnalysisFilter filter = AnalysisFilter.all();
    private Instant currentTime;
    private int cursor;
    private ReplayState state = ReplayState.NO_FILE;

    public HistoricalAnalysisService(
            ReceiverProfile receiver,
            AnalysisProfile profile,
            ZoneId sourceZone,
            AnalysisResultStore resultStore) {
        this(ignored -> receiver, profile,
                new HistoricalReplayLoader(sourceZone), resultStore,
                DefaultAnalysisEngine::new);
    }

    HistoricalAnalysisService(
            Function<java.time.LocalDate, ReceiverProfile> receiverResolver,
            AnalysisProfile profile,
            HistoricalReplayLoader loader,
            AnalysisResultStore resultStore,
            Supplier<AnalysisEngine> engineFactory) {
        this.receiverResolver = Objects.requireNonNull(
                receiverResolver, "receiverResolver");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore");
        this.engineFactory = Objects.requireNonNull(
                engineFactory, "engineFactory");
        analysisExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ais-replay-analysis");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<ReplayFrame> load(
            ais.input.history.HistoricalDaySelection selection,
            HistoricalReplayLoader.ProgressListener progress) {
        state = ReplayState.LOADING;
        return submit(() -> {
            HistoricalReplayDataset loaded = loader.load(
                    selection, progress);
            if (loaded.isEmpty()) {
                dataset = loaded;
                replayEngine = null;
                currentTime = null;
                cursor = 0;
                state = ReplayState.ERROR;
                return frame(null,
                        "有効なType 1/2/3/18/5/24がありません");
            }
            dataset = loaded;
            activeReceiver = Objects.requireNonNull(
                    receiverResolver.apply(selection.date()),
                    "no receiver profile is effective on "
                            + selection.date());
            rebuildTo(loaded.startTime());
            state = ReplayState.READY;
            return frame(snapshot(), "読込完了");
        });
    }

    public CompletableFuture<ReplayFrame> seek(Instant target) {
        Objects.requireNonNull(target, "target");
        return submit(() -> {
            requireDataset();
            state = ReplayState.SEEKING;
            Instant clamped = clamp(target);
            if (currentTime == null || clamped.isBefore(currentTime)) {
                rebuildTo(clamped);
            } else {
                acceptUntil(clamped);
                currentTime = clamped;
            }
            state = clamped.equals(dataset.endTime())
                    ? ReplayState.END : ReplayState.PAUSED;
            return frame(snapshot(), "");
        });
    }

    public CompletableFuture<ReplayFrame> advance(Duration simulatedTime) {
        Objects.requireNonNull(simulatedTime, "simulatedTime");
        if (simulatedTime.isNegative() || simulatedTime.isZero()) {
            throw new IllegalArgumentException(
                    "simulatedTime must be greater than zero");
        }
        return submit(() -> {
            requireDataset();
            Instant target = clamp(currentTime.plus(simulatedTime));
            acceptUntil(target);
            currentTime = target;
            state = target.equals(dataset.endTime())
                    ? ReplayState.END : ReplayState.PLAYING;
            return frame(snapshot(), "");
        });
    }

    public CompletableFuture<ReplayFrame> restart() {
        return submit(() -> {
            requireDataset();
            rebuildTo(dataset.startTime());
            state = ReplayState.READY;
            return frame(snapshot(), "先頭へ戻りました");
        });
    }

    public CompletableFuture<ReplayFrame> pause() {
        return submit(() -> {
            requireDataset();
            if (state != ReplayState.END) {
                state = ReplayState.PAUSED;
            }
            return frame(snapshot(), "");
        });
    }

    public CompletableFuture<ReplayFrame> setFilter(AnalysisFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return submit(() -> {
            this.filter = filter;
            if (dataset == null || dataset.isEmpty()) {
                return frame(null, "");
            }
            return frame(snapshot(), "");
        });
    }

    public CompletableFuture<FullDayAnalysisResult> analyzeAndSave() {
        return submit(() -> {
            requireDataset();
            ReplayState returnState = state == ReplayState.END
                    ? ReplayState.END : ReplayState.PAUSED;
            state = ReplayState.ANALYZING_DAY;
            try {
                FullDayAnalysisResult result = analyzeAndStore(
                        dataset, activeReceiver,
                        () -> Thread.currentThread().isInterrupted(),
                        () -> { });
                state = returnState;
                return result;
            } catch (Exception failure) {
                state = returnState;
                throw failure;
            }
        });
    }

    public CompletableFuture<HistoricalBatchResult> analyzeAndSaveBatch(
            List<HistoricalDaySelection> selections,
            boolean skipCompletedEquivalent,
            BatchProgressListener progress) {
        Objects.requireNonNull(selections, "selections");
        List<HistoricalDaySelection> ordered = selections.stream()
                .sorted(Comparator.comparing(HistoricalDaySelection::date))
                .toList();
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one historical day is required");
        }
        Set<LocalDate> dates = new HashSet<>();
        for (HistoricalDaySelection selection : ordered) {
            if (!dates.add(selection.date())) {
                throw new IllegalArgumentException(
                        "duplicate historical date: " + selection.date());
            }
        }
        if (!batchRunning.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "historical batch analysis is already running"));
        }
        batchCancellationRequested.set(false);
        BatchProgressListener listener = progress == null
                ? ignored -> { } : progress;
        CompletableFuture<HistoricalBatchResult> operation = submit(() ->
                analyzeBatch(ordered, skipCompletedEquivalent, listener));
        return operation.whenComplete((ignored, failure) -> {
            batchCancellationRequested.set(false);
            batchRunning.set(false);
        });
    }

    public void cancelBatchAnalysis() {
        if (batchRunning.get()) {
            batchCancellationRequested.set(true);
        }
    }

    public boolean isBatchAnalysisRunning() {
        return batchRunning.get();
    }

    public AnalysisFilter filter() {
        return filter;
    }

    @Override
    public void close() {
        batchCancellationRequested.set(true);
        analysisExecutor.shutdownNow();
    }

    private HistoricalBatchResult analyzeBatch(
            List<HistoricalDaySelection> selections,
            boolean skipCompletedEquivalent,
            BatchProgressListener progress) {
        Instant startedAt = Instant.now();
        int saved = 0;
        int skipped = 0;
        List<HistoricalBatchFailure> failures = new ArrayList<>();
        boolean cancelled = false;

        for (int index = 0; index < selections.size(); index++) {
            if (batchCancellationRequested.get()) {
                cancelled = true;
                break;
            }
            HistoricalDaySelection selection = selections.get(index);
            int dayNumber = index + 1;
            int savedBefore = saved;
            int skippedBefore = skipped;
            int failedBefore = failures.size();
            try {
                ReceiverProfile receiver = Objects.requireNonNull(
                        receiverResolver.apply(selection.date()),
                        "no receiver profile is effective on "
                                + selection.date());
                emitBatchProgress(progress, selection.date(),
                        HistoricalBatchProgress.Stage.FINGERPRINTING,
                        dayNumber, selections.size(), saved, skipped,
                        failures.size(), 0);
                InputFingerprint fingerprint =
                        new InputFingerprintCalculator().calculate(
                                selection.files(),
                                batchCancellationRequested::get);
                ensureNotCancelled(batchCancellationRequested::get);
                if (skipCompletedEquivalent
                        && resultStore.hasCompletedEquivalentRun(
                        selection.date(), fingerprint,
                        receiver.id(), profile.id())) {
                    skipped++;
                    emitBatchProgress(progress, selection.date(),
                            HistoricalBatchProgress.Stage.SKIPPED,
                            dayNumber, selections.size(), saved, skipped,
                            failures.size(), 0);
                    continue;
                }
                emitBatchProgress(progress, selection.date(),
                        HistoricalBatchProgress.Stage.LOADING,
                        dayNumber, selections.size(), saved, skipped,
                        failures.size(), 0);
                HistoricalReplayDataset loaded = loader.load(
                        selection, fingerprint, records -> {
                            emitBatchProgress(progress, selection.date(),
                                    HistoricalBatchProgress.Stage.LOADING,
                                    dayNumber, selections.size(),
                                    savedBefore, skippedBefore,
                                    failedBefore, records);
                            ensureNotCancelled(
                                    batchCancellationRequested::get);
                        });
                ensureNotCancelled(batchCancellationRequested::get);
                if (loaded.isEmpty()) {
                    throw new IllegalStateException(
                            "有効なType 1/2/3/18/5/24がありません");
                }
                emitBatchProgress(progress, selection.date(),
                        HistoricalBatchProgress.Stage.ANALYZING,
                        dayNumber, selections.size(), saved, skipped,
                        failures.size(), loaded.inputRecordCount());
                int progressSaved = saved;
                int progressSkipped = skipped;
                int progressFailed = failures.size();
                analyzeAndStore(loaded, receiver,
                        batchCancellationRequested::get,
                        () -> emitBatchProgress(
                                progress, selection.date(),
                                HistoricalBatchProgress.Stage.SAVING,
                                dayNumber, selections.size(),
                                progressSaved, progressSkipped,
                                progressFailed,
                                loaded.inputRecordCount()));
                saved++;
                emitBatchProgress(progress, selection.date(),
                        HistoricalBatchProgress.Stage.SAVED,
                        dayNumber, selections.size(), saved, skipped,
                        failures.size(), loaded.inputRecordCount());
            } catch (CancellationException cancelledDay) {
                cancelled = true;
                break;
            } catch (Exception failure) {
                failures.add(new HistoricalBatchFailure(
                        selection.date(), failureMessage(failure)));
                emitBatchProgress(progress, selection.date(),
                        HistoricalBatchProgress.Stage.FAILED,
                        dayNumber, selections.size(), saved, skipped,
                        failures.size(), 0);
            }
        }
        cancelled = cancelled || batchCancellationRequested.get();
        return new HistoricalBatchResult(
                selections.size(), saved, skipped, failures, cancelled,
                Duration.between(startedAt, Instant.now()));
    }

    private FullDayAnalysisResult analyzeAndStore(
            HistoricalReplayDataset source,
            ReceiverProfile receiver,
            BooleanSupplier cancelled,
            Runnable beforeSave) {
        AnalysisRunId runId = AnalysisRunId.create();
        AnalysisContext context = new AnalysisContext(
                receiver, profile, SourceMode.HISTORICAL,
                runId, source.startTime());
        AnalysisEngine fullDayEngine = engineFactory.get();
        fullDayEngine.begin(context);
        List<VesselMetadataObservation> metadata = new ArrayList<>();
        for (NormalizedAisEvent event : source.events()) {
            ensureNotCancelled(cancelled);
            var analysisEvents = fullDayEngine.accept(event);
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
        ensureNotCancelled(cancelled);
        AnalysisRunSummary summary = fullDayEngine.complete(
                source.endTime());
        AnalysisRun run = new AnalysisRun(
                runId, SourceMode.HISTORICAL,
                source.selection().date(),
                source.logicalInputName(), source.fingerprint(),
                receiver.id(), profile.id(), source.startTime());
        List<DiagnosticSummary> diagnostics =
                summarizeDiagnostics(source.diagnostics(), summary);
        beforeSave.run();
        ensureNotCancelled(cancelled);
        resultStore.replaceCompletedRun(
                run, summary, diagnostics, List.of(), metadata);
        return new FullDayAnalysisResult(
                runId, summary, source.diagnostics().size());
    }

    private static void ensureNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "historical batch analysis cancelled");
        }
    }

    private static void emitBatchProgress(
            BatchProgressListener listener,
            LocalDate date,
            HistoricalBatchProgress.Stage stage,
            int dayNumber,
            int totalDays,
            int savedDays,
            int skippedDays,
            int failedDays,
            long processedRecords) {
        listener.onProgress(new HistoricalBatchProgress(
                date, stage, dayNumber, totalDays,
                savedDays, skippedDays, failedDays, processedRecords));
    }

    private static String failureMessage(Throwable failure) {
        Throwable value = failure;
        while (value.getCause() != null && value.getCause() != value) {
            value = value.getCause();
        }
        String message = value.getMessage();
        return message == null || message.isBlank()
                ? value.getClass().getSimpleName() : message;
    }

    private void rebuildTo(Instant target) {
        replayEngine = engineFactory.get();
        replayEngine.begin(new AnalysisContext(
                activeReceiver, profile, SourceMode.HISTORICAL,
                AnalysisRunId.create(), dataset.startTime()));
        cursor = 0;
        currentTime = dataset.startTime();
        acceptUntil(target);
        currentTime = target;
    }

    private void acceptUntil(Instant target) {
        List<NormalizedAisEvent> events = dataset.events();
        while (cursor < events.size()
                && !events.get(cursor).receivedAt().isAfter(target)) {
            replayEngine.accept(events.get(cursor++));
        }
    }

    private AnalysisSnapshot snapshot() {
        return replayEngine.snapshot(currentTime, filter);
    }

    private ReplayFrame frame(AnalysisSnapshot snapshot, String message) {
        return new ReplayFrame(state, dataset, snapshot, currentTime,
                cursor, message);
    }

    private Instant clamp(Instant target) {
        if (target.isBefore(dataset.startTime())) {
            return dataset.startTime();
        }
        if (target.isAfter(dataset.endTime())) {
            return dataset.endTime();
        }
        return target;
    }

    private void requireDataset() {
        if (dataset == null || dataset.isEmpty()) {
            throw new IllegalStateException(
                    "a historical day must be loaded first");
        }
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

    private static List<DiagnosticSummary> summarizeDiagnostics(
            List<InputDiagnostic> input,
            AnalysisRunSummary summary) {
        Map<String, MutableDiagnostic> grouped = new LinkedHashMap<>();
        for (InputDiagnostic diagnostic : input) {
            grouped.computeIfAbsent(diagnostic.code().name(),
                            ignored -> new MutableDiagnostic())
                    .accept(diagnostic.occurredAt());
        }
        summary.excludedIntervalCounts().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry -> grouped.put(
                        "INTERVAL_" + entry.getKey().name(),
                        new MutableDiagnostic(entry.getValue(), null, null)));
        List<DiagnosticSummary> result = new ArrayList<>();
        grouped.forEach((code, value) -> result.add(
                new DiagnosticSummary(code, value.count,
                        value.first, value.last)));
        return List.copyOf(result);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface BatchProgressListener {
        void onProgress(HistoricalBatchProgress progress);
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

        private void accept(Instant at) {
            count++;
            if (first == null || at.isBefore(first)) {
                first = at;
            }
            if (last == null || at.isAfter(last)) {
                last = at;
            }
        }
    }
}
