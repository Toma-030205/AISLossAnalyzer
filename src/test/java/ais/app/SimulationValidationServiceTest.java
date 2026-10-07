package ais.app;

import ais.aggregate.MetricCounts;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.ConfidenceInterval;
import ais.simulation.calibration.ParameterApplicability;
import ais.simulation.validation.ObservedValidationCell;
import ais.simulation.validation.ObservedValidationDataset;
import ais.simulation.validation.ValidationCellKey;
import ais.simulation.validation.ValidationExperiment;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationRequest;
import ais.spatial.DistanceBand;
import ais.storage.CommunicationModelRepository;
import ais.storage.ObservedValidationQuery;
import ais.storage.ObservedValidationRepository;
import ais.storage.SimulationExperimentRepository;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SimulationValidationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2025, 12, 1);
    private static final CommunicationModelId MODEL_ID =
            CommunicationModelId.parse(
                    "00000000-0000-0000-0000-000000000401");

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesBothVariantsWithFixedSeedsAndSavesExperiment()
            throws Exception {
        Path log = temporaryDirectory.resolve("validation.ais");
        Files.writeString(log,
                line("20251201090000000", 34.6000)
                        + line("20251201090010000", 34.6001)
                        + line("20251201090020000", 34.6002)
                        + line("20251201090030000", 34.6003),
                StandardCharsets.UTF_8);
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        CommunicationModelSnapshot model = snapshot(profile);
        CapturingExperimentRepository experiments =
                new CapturingExperimentRepository();
        List<Integer> progress = new ArrayList<>();

        try (SimulationValidationService service =
                     new SimulationValidationService(
                             ignored -> receiver, profile,
                             Map.of(DAY, List.of(log)),
                             new HistoricalReplayLoader(
                                     ZoneId.of("Asia/Tokyo")),
                             new InMemoryModels(model),
                             new InMemoryObserved(), experiments)) {
            var result = service.validate(new ValidationRequest(
                            MODEL_ID, DAY, DAY, Set.of(),
                            2, 42L, false),
                    value -> progress.add(value.percent()))
                    .get(10, TimeUnit.SECONDS);

            assertEquals(List.of(42L, 43L), result.seeds());
            assertEquals(4, progress.size());
            assertEquals(100, progress.getLast());
            assertFalse(result.cells().isEmpty());
            assertNotNull(result.summary(
                    ValidationMetric.ESTIMATED_LOSS));
            assertNotNull(experiments.saved);
            assertEquals(4, experiments.saved.runs().size());
            assertEquals(result.experimentId(),
                    experiments.saved.result().experimentId());
        }
    }

    private static CommunicationModelSnapshot snapshot(
            AnalysisProfile profile) {
        var definition = new CommunicationModelDefinition(
                MODEL_ID, CommunicationModelCode.CM_E1, 1,
                "Validation model", new ReceiverProfileId("receiver"),
                profile.id(), LocalDate.of(2025, 11, 1),
                LocalDate.of(2025, 11, 30), "cm-e1-v1",
                1_000, 42L, Instant.parse("2026-10-06T00:00:00Z"),
                null);
        var parameter = new CommunicationParameter(
                new DistanceBand(0, 0.0, 5.0), VesselClass.CLASS_A,
                1_000_000, 0, 10, 30, 0.0, 1.0, 1.0,
                new ConfidenceInterval(0.99, 1.0),
                ParameterApplicability.DIRECT, 1.0, null, null);
        return new CommunicationModelSnapshot(
                definition, Map.of(), List.of(), List.of(parameter));
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.6000, 135.2000), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
    }

    private static String line(String timestamp, double latitude) {
        String payload = AisTestData.type1(
                1, 431_000_001, latitude, 135.2);
        return timestamp + " " + AisTestData.sentence(payload, 0) + "\n";
    }

    private record InMemoryModels(CommunicationModelSnapshot snapshot)
            implements CommunicationModelRepository {

        @Override
        public CommunicationModelDefinition save(
                CommunicationModelDraft draft, String name, String notes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CommunicationModelDefinition> findAll() {
            return List.of(snapshot.definition());
        }

        @Override
        public Optional<CommunicationModelSnapshot> findById(
                CommunicationModelId modelId) {
            return snapshot.definition().id().equals(modelId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
    }

    private static final class InMemoryObserved
            implements ObservedValidationRepository {

        @Override
        public ObservedValidationDataset load(
                ObservedValidationQuery query) {
            ValidationCellKey key = new ValidationCellKey(
                    new DistanceBand(0, 0.0, 5.0), VesselClass.CLASS_A);
            return new ObservedValidationDataset(
                    List.of(AnalysisRunId.create()),
                    Map.of(key, new ObservedValidationCell(
                            key, new MetricCounts(3, 0, 30.0, 0.0),
                            1, 1, List.of(0.0), List.of(0.0))));
        }
    }

    private static final class CapturingExperimentRepository
            implements SimulationExperimentRepository {
        private ValidationExperiment saved;

        @Override
        public void save(ValidationExperiment experiment) {
            saved = experiment;
        }
    }
}
