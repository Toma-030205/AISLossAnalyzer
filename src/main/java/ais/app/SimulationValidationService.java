package ais.app;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.MetricCounts;
import ais.analysis.AnalysisEngine;
import ais.analysis.DefaultAnalysisEngine;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.ReceiverProfile;
import ais.domain.SourceMode;
import ais.domain.VesselMetadataUpdate;
import ais.input.history.HistoricalDaySelection;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.communication.ClassOnlyBaselineModel;
import ais.simulation.communication.CommunicationModel;
import ais.simulation.communication.EmpiricalDistanceClassModel;
import ais.simulation.communication.ReceptionContext;
import ais.simulation.communication.ReceptionOutcome;
import ais.simulation.communication.SimulationReceptionProcessor;
import ais.simulation.traffic.IdealTransmission;
import ais.simulation.traffic.IdealTransmissionDay;
import ais.simulation.traffic.IdealTransmissionGenerator;
import ais.simulation.validation.ObservedValidationDataset;
import ais.simulation.validation.SimulationExperimentId;
import ais.simulation.validation.ValidationCellKey;
import ais.simulation.validation.ValidationComparator;
import ais.simulation.validation.ValidationExperiment;
import ais.simulation.validation.ValidationModelVariant;
import ais.simulation.validation.ValidationProgress;
import ais.simulation.validation.ValidationRequest;
import ais.simulation.validation.ValidationResult;
import ais.simulation.validation.ValidationRunResult;
import ais.storage.CommunicationModelRepository;
import ais.storage.ObservedValidationQuery;
import ais.storage.ObservedValidationRepository;
import ais.storage.SimulationExperimentRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SimulationValidationService implements AutoCloseable {

    private final Function<LocalDate, ReceiverProfile> receiverResolver;
    private final AnalysisProfile profile;
    private final Map<LocalDate, List<Path>> historicalFiles;
    private final HistoricalReplayLoader loader;
    private final CommunicationModelRepository models;
    private final ObservedValidationRepository observedRepository;
    private final SimulationExperimentRepository experimentRepository;
    private final IdealTransmissionGenerator generator;
    private final Supplier<AnalysisEngine> engineFactory;
    private final ValidationComparator comparator;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

    public SimulationValidationService(
            Function<LocalDate, ReceiverProfile> receiverResolver,
            AnalysisProfile profile,
            Map<LocalDate, List<Path>> historicalFiles,
            HistoricalReplayLoader loader,
            CommunicationModelRepository models,
            ObservedValidationRepository observedRepository,
            SimulationExperimentRepository experimentRepository) {
        this(receiverResolver, profile, historicalFiles, loader, models,
                observedRepository, experimentRepository,
                new IdealTransmissionGenerator(),
                DefaultAnalysisEngine::new, new ValidationComparator());
    }

    SimulationValidationService(
            Function<LocalDate, ReceiverProfile> receiverResolver,
            AnalysisProfile profile,
            Map<LocalDate, List<Path>> historicalFiles,
            HistoricalReplayLoader loader,
            CommunicationModelRepository models,
            ObservedValidationRepository observedRepository,
            SimulationExperimentRepository experimentRepository,
            IdealTransmissionGenerator generator,
            Supplier<AnalysisEngine> engineFactory,
            ValidationComparator comparator) {
        this.receiverResolver = Objects.requireNonNull(
                receiverResolver, "receiverResolver");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.historicalFiles = Map.copyOf(historicalFiles);
        this.loader = Objects.requireNonNull(loader, "loader");
        this.models = Objects.requireNonNull(models, "models");
        this.observedRepository = Objects.requireNonNull(
                observedRepository, "observedRepository");
        this.experimentRepository = Objects.requireNonNull(
                experimentRepository, "experimentRepository");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.engineFactory = Objects.requireNonNull(
                engineFactory, "engineFactory");
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ais-simulation-validation");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<List<CommunicationModelDefinition>> findModels() {
        return CompletableFuture.supplyAsync(models::findAll, executor);
    }

    public CompletableFuture<ValidationResult> validate(
            ValidationRequest request,
            ProgressListener progressListener) {
        Objects.requireNonNull(request, "request");
        ProgressListener listener = progressListener == null
                ? ignored -> { } : progressListener;
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("妥当性確認は既に実行中です"));
        }
        cancellationRequested.set(false);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return validateNow(request, listener);
            } finally {
                running.set(false);
                cancellationRequested.set(false);
            }
        }, executor);
    }

    public boolean cancel() {
        if (!running.get()) {
            return false;
        }
        cancellationRequested.set(true);
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    private ValidationResult validateNow(
            ValidationRequest request,
            ProgressListener listener) {
        Instant createdAt = Instant.now();
        CommunicationModelSnapshot snapshot = models.findById(
                        request.modelId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "通信モデルが見つかりません: " + request.modelId()));
        requireCompatible(request, snapshot);
        List<LocalDate> dates = includedDates(request);
        requireFiles(dates);
        ReceiverProfile observedReceiver = Objects.requireNonNull(
                receiverResolver.apply(dates.getFirst()),
                "検証期間に有効な受信局がありません");
        if (!observedReceiver.id().equals(
                snapshot.definition().receiverProfileId())) {
            throw new IllegalArgumentException(
                    "検証期間の受信局がモデルの受信局と一致しません");
        }
        ObservedValidationDataset observed = observedRepository.load(
                new ObservedValidationQuery(
                        request.startDate(), request.endDate(),
                        observedReceiver.id(), profile.id(),
                        request.excludedDates()));
        if (observed.cells().isEmpty()) {
            throw new IllegalStateException(
                    "検証期間の実測解析結果がSQLiteにありません");
        }

        List<MutableRun> accumulators = createRuns(request);
        MessageDigest fingerprint = sha256();
        int totalUnits = dates.size() * request.iterationCount() * 2;
        int completedUnits = 0;
        for (LocalDate date : dates) {
            checkCancellation();
            ReceiverProfile receiver = Objects.requireNonNull(
                    receiverResolver.apply(date),
                    date + "に有効な受信局がありません");
            if (!receiver.id().equals(snapshot.definition()
                    .receiverProfileId())) {
                throw new IllegalArgumentException(
                        date + "の受信局がモデルの受信局と一致しません");
            }
            HistoricalReplayDataset dataset = loadDay(date);
            if (dataset.isEmpty()) {
                throw new IllegalStateException(
                        date + "の有効なAISイベントがありません");
            }
            updateFingerprint(fingerprint, dataset);
            IdealTransmissionDay ideal = generator.generate(
                    dataset, receiver, profile);
            if (ideal.transmissions().isEmpty()) {
                throw new IllegalStateException(
                        date + "に理想送信列を生成できません");
            }
            for (MutableRun run : accumulators) {
                checkCancellation();
                CommunicationModel model = run.variant
                        == ValidationModelVariant.CM_E1
                        ? new EmpiricalDistanceClassModel(snapshot, profile)
                        : new ClassOnlyBaselineModel(snapshot, profile);
                DayRun dayRun = runDay(
                        dataset, ideal, receiver, snapshot, run.seed, model);
                run.merge(dayRun);
                completedUnits++;
                listener.onProgress(new ValidationProgress(
                        completedUnits, totalUnits, date,
                        run.iteration + 1, run.variant,
                        date + " / " + run.variant));
            }
        }
        checkCancellation();
        List<ValidationRunResult> runs = accumulators.stream()
                .map(MutableRun::freeze).toList();
        ValidationComparator.Comparison comparison = comparator.compare(
                observed, runs);
        List<String> warnings = warnings(request, dates, observed, runs);
        Instant completedAt = Instant.now();
        ValidationResult result = new ValidationResult(
                SimulationExperimentId.create(), request,
                snapshot.definition(), createdAt, completedAt,
                accumulators.stream().filter(run ->
                        run.variant == ValidationModelVariant.CM_E1)
                        .map(run -> run.seed).toList(),
                observed.sourceRunIds(), comparison.cells(),
                comparison.summaries(), warnings,
                HexFormat.of().formatHex(fingerprint.digest()));
        experimentRepository.save(new ValidationExperiment(result, runs));
        return result;
    }

    private DayRun runDay(
            HistoricalReplayDataset dataset,
            IdealTransmissionDay ideal,
            ReceiverProfile receiver,
            CommunicationModelSnapshot snapshot,
            long seed,
            CommunicationModel model) {
        AnalysisEngine engine = engineFactory.get();
        engine.begin(new AnalysisContext(
                receiver, profile, SourceMode.SIMULATION,
                AnalysisRunId.create(), dataset.startTime()));
        SimulationReceptionProcessor processor =
                new SimulationReceptionProcessor(
                        model,
                        new ReceptionContext(
                                receiver, snapshot.definition().id(), seed),
                        engine);
        int transmissionCursor = 0;
        int metadataCursor = 0;
        long outOfModel = 0;
        List<IdealTransmission> transmissions = ideal.transmissions();
        List<VesselMetadataUpdate> metadata = ideal.metadataUpdates();
        while (transmissionCursor < transmissions.size()
                || metadataCursor < metadata.size()) {
            if (((transmissionCursor + metadataCursor) & 1023) == 0) {
                checkCancellation();
            }
            IdealTransmission transmission = transmissionCursor
                    < transmissions.size()
                    ? transmissions.get(transmissionCursor) : null;
            VesselMetadataUpdate update = metadataCursor < metadata.size()
                    ? metadata.get(metadataCursor) : null;
            boolean metadataFirst = update != null
                    && (transmission == null
                    || !update.receivedAt().isAfter(
                            transmission.plannedAt()));
            if (metadataFirst) {
                processor.acceptMetadata(update);
                metadataCursor++;
            } else {
                var step = processor.accept(transmission);
                if (step.decision().outcome()
                        == ReceptionOutcome.OUT_OF_MODEL) {
                    outOfModel++;
                }
                transmissionCursor++;
            }
        }
        var summary = engine.complete(dataset.endTime());
        Map<ValidationCellKey, MetricCounts> metrics =
                new LinkedHashMap<>();
        summary.aggregation().distanceMetrics().entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> new ValidationCellKey(
                                entry.getKey().spatialKey(),
                                entry.getKey().vesselClass())))
                .forEach(entry -> mergeMetric(metrics,
                        entry.getKey(), entry.getValue()));
        return new DayRun(metrics, outOfModel);
    }

    private HistoricalReplayDataset loadDay(LocalDate date) {
        try {
            return loader.load(new HistoricalDaySelection(
                    date, historicalFiles.get(date), false),
                    processed -> { });
        } catch (IOException failure) {
            throw new IllegalStateException(
                    date + "の過去ログを読み込めません: "
                            + failure.getMessage(), failure);
        }
    }

    private static void mergeMetric(
            Map<ValidationCellKey, MetricCounts> target,
            AggregateKey<ais.spatial.DistanceBand> key,
            AggregateMetric metric) {
        ValidationCellKey validationKey = new ValidationCellKey(
                key.spatialKey(), key.vesselClass());
        target.merge(validationKey, metric.counts(), MetricCounts::plus);
    }

    private void requireCompatible(
            ValidationRequest request,
            CommunicationModelSnapshot snapshot) {
        var definition = snapshot.definition();
        if (!definition.analysisProfileId().equals(profile.id())) {
            throw new IllegalArgumentException(
                    "通信モデルと解析条件が一致しません");
        }
        boolean overlapsTraining = !request.endDate().isBefore(
                definition.trainingStartDate())
                && !request.startDate().isAfter(
                definition.trainingEndDate());
        if (overlapsTraining) {
            throw new IllegalArgumentException(
                    "学習期間と検証期間を重ねることはできません（学習: "
                            + definition.trainingStartDate() + "～"
                            + definition.trainingEndDate() + "）");
        }
        if (request.includedDayCount() == 0) {
            throw new IllegalArgumentException("検証対象日がありません");
        }
    }

    private void requireFiles(List<LocalDate> dates) {
        List<LocalDate> missing = dates.stream()
                .filter(date -> !historicalFiles.containsKey(date)
                        || historicalFiles.get(date).isEmpty())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "過去ログがない日があります: " + missing);
        }
    }

    private static List<LocalDate> includedDates(ValidationRequest request) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = request.startDate();
                !date.isAfter(request.endDate()); date = date.plusDays(1)) {
            if (!request.excludedDates().contains(date)) {
                dates.add(date);
            }
        }
        return List.copyOf(dates);
    }

    private static List<MutableRun> createRuns(ValidationRequest request) {
        List<MutableRun> runs = new ArrayList<>();
        for (int iteration = 0; iteration < request.iterationCount();
                iteration++) {
            long seed = Math.addExact(request.seedBase(), iteration);
            runs.add(new MutableRun(iteration, seed,
                    ValidationModelVariant.CM_E1));
            runs.add(new MutableRun(iteration, seed,
                    ValidationModelVariant.CLASS_ONLY_BASELINE));
        }
        return runs;
    }

    private static List<String> warnings(
            ValidationRequest request,
            List<LocalDate> dates,
            ObservedValidationDataset observed,
            List<ValidationRunResult> runs) {
        List<String> values = new ArrayList<>();
        if (request.iterationCount() < 30) {
            values.add("正式評価の既定値30回より反復回数が少なくなっています");
        }
        if (observed.sourceRunIds().size() < dates.size()) {
            values.add("対象日数より実測解析runが少ないため、未解析日を確認してください");
        }
        long outOfModel = runs.stream()
                .filter(run -> run.variant() == ValidationModelVariant.CM_E1)
                .mapToLong(ValidationRunResult::outOfModelCount).sum();
        if (outOfModel > 0) {
            values.add("CM-E1の適用外判定が" + outOfModel
                    + "件あります（全反復合計）");
        }
        return List.copyOf(values);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void updateFingerprint(
            MessageDigest digest,
            HistoricalReplayDataset dataset) {
        String value = dataset.selection().date() + "|"
                + dataset.fingerprint().sha256() + "|"
                + dataset.fingerprint().uncompressedBytes() + "|"
                + dataset.fingerprint().fileCount() + "\n";
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private void checkCancellation() {
        if (cancellationRequested.get()) {
            throw new CancellationException("妥当性確認を中止しました");
        }
    }

    @Override
    public void close() {
        cancellationRequested.set(true);
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(ValidationProgress progress);
    }

    private record DayRun(
            Map<ValidationCellKey, MetricCounts> metrics,
            long outOfModelCount) {

        private DayRun {
            metrics = Map.copyOf(metrics);
        }
    }

    private static final class MutableRun {
        private final int iteration;
        private final long seed;
        private final ValidationModelVariant variant;
        private final Instant startedAt = Instant.now();
        private final Map<ValidationCellKey, MetricCounts> metrics =
                new LinkedHashMap<>();
        private long outOfModelCount;

        private MutableRun(
                int iteration,
                long seed,
                ValidationModelVariant variant) {
            this.iteration = iteration;
            this.seed = seed;
            this.variant = variant;
        }

        private void merge(DayRun day) {
            day.metrics.forEach((key, counts) -> metrics.merge(
                    key, counts, MetricCounts::plus));
            outOfModelCount = Math.addExact(
                    outOfModelCount, day.outOfModelCount);
        }

        private ValidationRunResult freeze() {
            return new ValidationRunResult(
                    iteration, seed, variant, outOfModelCount,
                    startedAt, Instant.now(), metrics);
        }
    }
}
