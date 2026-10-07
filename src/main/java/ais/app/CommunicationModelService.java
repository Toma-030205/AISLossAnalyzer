package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.simulation.calibration.CalibrationDataset;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationModelTrainer;
import ais.simulation.calibration.CommunicationTrainingRequest;
import ais.storage.AnalysisProfileRepository;
import ais.storage.CommunicationCalibrationRepository;
import ais.storage.CommunicationModelRepository;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcCommunicationCalibrationRepository;
import ais.storage.JdbcCommunicationModelRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.ReceiverProfileRepository;
import ais.storage.SqliteDatabase;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CommunicationModelService implements AutoCloseable {

    private final CommunicationCalibrationRepository calibration;
    private final CommunicationModelRepository models;
    private final ReceiverProfileRepository receivers;
    private final AnalysisProfileRepository profiles;
    private final CommunicationModelTrainer trainer;
    private final ExecutorService executor;

    public CommunicationModelService(SqliteDatabase database) {
        this(new JdbcCommunicationCalibrationRepository(database),
                new JdbcCommunicationModelRepository(database),
                new JdbcReceiverProfileRepository(database),
                new JdbcAnalysisProfileRepository(database),
                new CommunicationModelTrainer());
    }

    CommunicationModelService(
            CommunicationCalibrationRepository calibration,
            CommunicationModelRepository models,
            ReceiverProfileRepository receivers,
            AnalysisProfileRepository profiles,
            CommunicationModelTrainer trainer) {
        this.calibration = Objects.requireNonNull(
                calibration, "calibration");
        this.models = Objects.requireNonNull(models, "models");
        this.receivers = Objects.requireNonNull(receivers, "receivers");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.trainer = Objects.requireNonNull(trainer, "trainer");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "ais-communication-model");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<CommunicationModelDraft> preview(
            CommunicationTrainingRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(
                () -> previewNow(request), executor);
    }

    public CompletableFuture<CommunicationModelDefinition> save(
            CommunicationModelDraft draft,
            String name,
            String notes) {
        Objects.requireNonNull(draft, "draft");
        return CompletableFuture.supplyAsync(() -> {
            if (draft.sourceRunIds().isEmpty()) {
                throw new IllegalStateException(
                        "根拠となる解析runがないため保存できません");
            }
            if (draft.directParameterCount() == 0) {
                throw new IllegalStateException(
                        "直接採用できるセルがないため保存できません");
            }
            return models.save(draft, name, notes);
        }, executor);
    }

    public CompletableFuture<List<CommunicationModelDefinition>> findAll() {
        return CompletableFuture.supplyAsync(models::findAll, executor);
    }

    public CompletableFuture<CommunicationModelSnapshot> load(
            CommunicationModelId modelId) {
        Objects.requireNonNull(modelId, "modelId");
        return CompletableFuture.supplyAsync(() -> models.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "通信モデルが見つかりません: " + modelId)), executor);
    }

    public List<ReceiverProfile> receiverProfiles() {
        return receivers.findAll();
    }

    public List<AnalysisProfile> analysisProfiles() {
        return profiles.findAll();
    }

    CommunicationModelDraft previewNow(
            CommunicationTrainingRequest request) {
        ReceiverProfile receiver = receivers.findById(
                        request.receiverProfileId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "受信局プロファイルが見つかりません"));
        AnalysisProfile profile = profiles.findById(
                        request.analysisProfileId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "解析条件が見つかりません"));
        if (!receiver.isEffectiveOn(request.startDate())
                || !receiver.isEffectiveOn(request.endDate())) {
            throw new IllegalArgumentException(
                    "学習期間が受信局プロファイルの有効期間外です");
        }
        CalibrationDataset dataset = calibration.load(request);
        return trainer.train(request, receiver, profile, dataset);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
