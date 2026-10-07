package ais.app;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.AggregationSnapshot;
import ais.aggregate.MetricCounts;
import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.input.history.InputFingerprint;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationTrainingRequest;
import ais.simulation.calibration.ParameterApplicability;
import ais.spatial.DistanceBand;
import ais.storage.AnalysisResultStore;
import ais.storage.AnalysisRun;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationModelServiceTest {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final LocalDate START = LocalDate.of(2025, 11, 1);

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private ReceiverProfile receiver;
    private AnalysisProfile profile;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabase(
                temporaryDirectory.resolve("calibration.db"));
        new SchemaMigrator(database).migrate();
        receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Research receiver",
                new GeoPosition(34.7, 135.2), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
        profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        for (int day = 0; day < 5; day++) {
            saveRun(START.plusDays(day), day + 1);
        }
    }

    @Test
    void previewsSavesAndReloadsImmutableModel() {
        try (CommunicationModelService service =
                     new CommunicationModelService(database)) {
            CommunicationTrainingRequest request =
                    new CommunicationTrainingRequest(
                            CommunicationModelCode.CM_E1,
                            START, START.plusDays(4), Set.of(),
                            receiver.id(), profile.id(), 500, 1234L);

            CommunicationModelDraft draft = service.preview(request).join();

            assertEquals(5, draft.sourceRunIds().size());
            assertTrue(draft.missingDates().isEmpty());
            assertEquals(1, draft.directParameterCount());
            assertEquals(ParameterApplicability.DIRECT,
                    draft.parameters().getFirst().applicability());

            CommunicationModelDefinition first = service.save(
                    draft, "November CM-E1", "test model").join();
            CommunicationModelDefinition second = service.save(
                    draft, "November CM-E1 rerun", null).join();
            CommunicationModelSnapshot loaded = service.load(
                    first.id()).join();

            assertEquals(1, first.revision());
            assertEquals(2, second.revision());
            assertEquals(first, loaded.definition());
            assertEquals(draft.sourceRunIds().size(),
                    loaded.sourceRunIds().size());
            assertEquals(draft.parameters(), loaded.parameters());
            assertEquals(2, service.findAll().join().size());
        }
    }

    @Test
    void failedParameterInsertRollsBackTheWholeModel() {
        try (CommunicationModelService service =
                     new CommunicationModelService(database)) {
            CommunicationTrainingRequest request =
                    new CommunicationTrainingRequest(
                            CommunicationModelCode.CM_E1,
                            START, START.plusDays(4), Set.of(),
                            receiver.id(), profile.id(), 100, 9L);
            CommunicationModelDraft original = service.preview(request).join();
            var duplicate = original.parameters().getFirst();
            var invalidParameters = new java.util.ArrayList<>(
                    original.parameters());
            invalidParameters.add(duplicate);
            CommunicationModelDraft invalid = new CommunicationModelDraft(
                    original.request(), original.receiverProfile(),
                    original.analysisProfile(), original.formulaVersion(),
                    invalidParameters, original.sourceRunIds(),
                    original.missingDates(), original.warnings(),
                    original.generatedAt());

            assertThrows(CompletionException.class, () -> service.save(
                    invalid, "must roll back", null).join());
            assertTrue(service.findAll().join().isEmpty());
        }
    }

    private void saveRun(LocalDate date, int fingerprintValue) {
        Instant startedAt = date.atStartOfDay(JAPAN).toInstant();
        AnalysisRun run = new AnalysisRun(
                AnalysisRunId.create(), SourceMode.HISTORICAL,
                date, date + ".log",
                new InputFingerprint(
                        String.format("%064x", fingerprintValue),
                        10_000 + fingerprintValue, 1),
                receiver.id(), profile.id(), startedAt);
        DistanceBand band = new DistanceBand(0, 0, 5);
        AggregateKey<DistanceBand> key = new AggregateKey<>(
                startedAt.plusSeconds(300), band, VesselClass.CLASS_A);
        AggregationSnapshot aggregation = new AggregationSnapshot(
                Map.of(),
                Map.of(key, new AggregateMetric(
                        new MetricCounts(80, 20, 100, 20),
                        Set.of(431000001, 431000002, 431000003))),
                0);
        AnalysisContext context = new AnalysisContext(
                receiver, profile, SourceMode.HISTORICAL,
                run.id(), startedAt);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                context, startedAt.plusSeconds(600),
                100, 20, 0, Map.of(), aggregation);
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, List.of(), List.of());
    }
}
