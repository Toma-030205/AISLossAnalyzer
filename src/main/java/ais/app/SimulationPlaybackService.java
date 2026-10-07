package ais.app;

import ais.analysis.AnalysisEngine;
import ais.analysis.AnalysisFilter;
import ais.analysis.AnalysisSnapshot;
import ais.analysis.DefaultAnalysisEngine;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.ReceiverProfile;
import ais.domain.SourceMode;
import ais.domain.VesselMetadataUpdate;
import ais.input.history.HistoricalDaySelection;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.communication.EmpiricalDistanceClassModel;
import ais.simulation.communication.ReceptionContext;
import ais.simulation.communication.ReceptionDecision;
import ais.simulation.communication.ReceptionOutcome;
import ais.simulation.communication.SimulationReceptionProcessor;
import ais.simulation.traffic.IdealTransmission;
import ais.simulation.traffic.IdealTransmissionDay;
import ais.simulation.traffic.IdealTransmissionGenerator;
import ais.simulation.traffic.SimulationTruthStore;
import ais.storage.CommunicationModelRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SimulationPlaybackService implements AutoCloseable {

    private final Function<LocalDate, ReceiverProfile> receiverResolver;
    private final AnalysisProfile profile;
    private final HistoricalReplayLoader loader;
    private final CommunicationModelRepository models;
    private final IdealTransmissionGenerator transmissionGenerator;
    private final Supplier<AnalysisEngine> engineFactory;
    private final ExecutorService executor;

    private HistoricalReplayDataset sourceDataset;
    private IdealTransmissionDay day;
    private CommunicationModelSnapshot modelSnapshot;
    private ReceiverProfile receiver;
    private long seed;
    private AnalysisFilter filter = AnalysisFilter.all();
    private AnalysisEngine analysisEngine;
    private SimulationReceptionProcessor receptionProcessor;
    private SimulationTruthStore truthStore;
    private final Map<Integer, ReceptionDecision> lastDecisions =
            new LinkedHashMap<>();
    private Instant currentTime;
    private int transmissionCursor;
    private int metadataCursor;
    private long receivedCount;
    private long lostCount;
    private long outOfModelCount;
    private SimulationPlaybackState state = SimulationPlaybackState.NO_FILE;

    public SimulationPlaybackService(
            Function<LocalDate, ReceiverProfile> receiverResolver,
            AnalysisProfile profile,
            HistoricalReplayLoader loader,
            CommunicationModelRepository models) {
        this(receiverResolver, profile, loader, models,
                new IdealTransmissionGenerator(), DefaultAnalysisEngine::new);
    }

    SimulationPlaybackService(
            Function<LocalDate, ReceiverProfile> receiverResolver,
            AnalysisProfile profile,
            HistoricalReplayLoader loader,
            CommunicationModelRepository models,
            IdealTransmissionGenerator transmissionGenerator,
            Supplier<AnalysisEngine> engineFactory) {
        this.receiverResolver = Objects.requireNonNull(
                receiverResolver, "receiverResolver");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.models = Objects.requireNonNull(models, "models");
        this.transmissionGenerator = Objects.requireNonNull(
                transmissionGenerator, "transmissionGenerator");
        this.engineFactory = Objects.requireNonNull(
                engineFactory, "engineFactory");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "ais-simulation-playback");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<List<CommunicationModelDefinition>> findModels() {
        return submit(models::findAll);
    }

    public CompletableFuture<SimulationFrame> load(
            HistoricalDaySelection selection,
            CommunicationModelId modelId,
            long seed,
            HistoricalReplayLoader.ProgressListener progress) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(modelId, "modelId");
        state = SimulationPlaybackState.LOADING;
        return submit(() -> {
            HistoricalReplayDataset loaded = loader.load(selection, progress);
            if (loaded.isEmpty()) {
                state = SimulationPlaybackState.ERROR;
                throw new IllegalArgumentException(
                        "有効なAISイベントがありません");
            }
            CommunicationModelSnapshot loadedModel = models.findById(modelId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "通信モデルが見つかりません: " + modelId));
            ReceiverProfile activeReceiver = Objects.requireNonNull(
                    receiverResolver.apply(selection.date()),
                    "no receiver profile is effective on "
                            + selection.date());
            IdealTransmissionDay generated = transmissionGenerator.generate(
                    loaded, activeReceiver, profile);
            if (generated.transmissions().isEmpty()) {
                state = SimulationPlaybackState.ERROR;
                throw new IllegalArgumentException(
                        "Type 1/2/3/18の位置通報がありません");
            }
            new EmpiricalDistanceClassModel(loadedModel, profile)
                    .decide(generated.transmissions().getFirst(),
                            new ReceptionContext(
                                    activeReceiver, modelId, seed));
            sourceDataset = loaded;
            day = generated;
            modelSnapshot = loadedModel;
            receiver = activeReceiver;
            this.seed = seed;
            rebuildTo(loaded.startTime());
            state = SimulationPlaybackState.READY;
            return frame("読込完了");
        });
    }

    public CompletableFuture<SimulationFrame> seek(Instant target) {
        Objects.requireNonNull(target, "target");
        return submit(() -> {
            requireLoaded();
            state = SimulationPlaybackState.SEEKING;
            Instant clamped = clamp(target);
            if (currentTime == null || clamped.isBefore(currentTime)) {
                rebuildTo(clamped);
            } else {
                acceptUntil(clamped);
                currentTime = clamped;
            }
            state = clamped.equals(sourceDataset.endTime())
                    ? SimulationPlaybackState.END
                    : SimulationPlaybackState.PAUSED;
            return frame("");
        });
    }

    public CompletableFuture<SimulationFrame> advance(
            Duration simulatedTime) {
        Objects.requireNonNull(simulatedTime, "simulatedTime");
        if (simulatedTime.isZero() || simulatedTime.isNegative()) {
            throw new IllegalArgumentException(
                    "simulatedTime must be greater than zero");
        }
        return submit(() -> {
            requireLoaded();
            Instant target = clamp(currentTime.plus(simulatedTime));
            acceptUntil(target);
            currentTime = target;
            state = target.equals(sourceDataset.endTime())
                    ? SimulationPlaybackState.END
                    : SimulationPlaybackState.PLAYING;
            return frame("");
        });
    }

    public CompletableFuture<SimulationFrame> restart() {
        return submit(() -> {
            requireLoaded();
            rebuildTo(sourceDataset.startTime());
            state = SimulationPlaybackState.READY;
            return frame("先頭へ戻りました");
        });
    }

    public CompletableFuture<SimulationFrame> pause() {
        return submit(() -> {
            requireLoaded();
            if (state != SimulationPlaybackState.END) {
                state = SimulationPlaybackState.PAUSED;
            }
            return frame("");
        });
    }

    public CompletableFuture<SimulationFrame> setFilter(
            AnalysisFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return submit(() -> {
            this.filter = filter;
            return day == null ? emptyFrame("") : frame("");
        });
    }

    private void rebuildTo(Instant target) {
        analysisEngine = engineFactory.get();
        analysisEngine.begin(new AnalysisContext(
                receiver,
                profile,
                SourceMode.SIMULATION,
                AnalysisRunId.create(),
                sourceDataset.startTime()));
        receptionProcessor = new SimulationReceptionProcessor(
                new EmpiricalDistanceClassModel(modelSnapshot, profile),
                new ReceptionContext(
                        receiver, modelSnapshot.definition().id(), seed),
                analysisEngine);
        truthStore = new SimulationTruthStore();
        lastDecisions.clear();
        transmissionCursor = 0;
        metadataCursor = 0;
        receivedCount = 0;
        lostCount = 0;
        outOfModelCount = 0;
        currentTime = sourceDataset.startTime();
        acceptUntil(target);
        currentTime = target;
    }

    private void acceptUntil(Instant target) {
        List<IdealTransmission> transmissions = day.transmissions();
        List<VesselMetadataUpdate> metadata = day.metadataUpdates();
        while (true) {
            IdealTransmission transmission = transmissionCursor
                    < transmissions.size()
                    ? transmissions.get(transmissionCursor) : null;
            VesselMetadataUpdate update = metadataCursor < metadata.size()
                    ? metadata.get(metadataCursor) : null;
            boolean metadataFirst = update != null
                    && (transmission == null
                    || !update.receivedAt().isAfter(
                            transmission.plannedAt()));
            Instant nextTime = metadataFirst
                    ? update.receivedAt()
                    : transmission == null ? null : transmission.plannedAt();
            if (nextTime == null || nextTime.isAfter(target)) {
                return;
            }
            if (metadataFirst) {
                receptionProcessor.acceptMetadata(update);
                metadataCursor++;
                continue;
            }
            truthStore.accept(transmission);
            var result = receptionProcessor.accept(transmission);
            ReceptionDecision decision = result.decision();
            lastDecisions.put(transmission.mmsi(), decision);
            switch (decision.outcome()) {
                case RECEIVED -> receivedCount++;
                case LOST -> lostCount++;
                case OUT_OF_MODEL -> outOfModelCount++;
            }
            transmissionCursor++;
        }
    }

    private AnalysisSnapshot snapshot() {
        return analysisEngine.snapshot(currentTime, filter);
    }

    private SimulationFrame frame(String message) {
        AnalysisSnapshot snapshot = snapshot();
        return new SimulationFrame(
                state,
                day,
                modelSnapshot.definition(),
                seed,
                sourceDataset.startTime(),
                sourceDataset.endTime(),
                currentTime,
                snapshot,
                truthStore.snapshot(currentTime, filter),
                lastDecisions,
                diagnostics(),
                message);
    }

    private SimulationFrame emptyFrame(String message) {
        return new SimulationFrame(
                state, null, null, seed, null, null, null, null,
                Map.of(), Map.of(),
                new SimulationDiagnostics(0, 0, 0, 0, 0),
                message);
    }

    private SimulationDiagnostics diagnostics() {
        return new SimulationDiagnostics(
                transmissionCursor,
                receivedCount,
                lostCount,
                outOfModelCount,
                metadataCursor);
    }

    private Instant clamp(Instant target) {
        if (target.isBefore(sourceDataset.startTime())) {
            return sourceDataset.startTime();
        }
        if (target.isAfter(sourceDataset.endTime())) {
            return sourceDataset.endTime();
        }
        return target;
    }

    private void requireLoaded() {
        if (day == null || analysisEngine == null) {
            throw new IllegalStateException(
                    "a simulation day must be loaded first");
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
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
