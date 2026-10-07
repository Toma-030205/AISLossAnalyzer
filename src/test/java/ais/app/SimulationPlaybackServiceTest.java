package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.input.history.HistoricalDaySelection;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.ConfidenceInterval;
import ais.simulation.calibration.ParameterApplicability;
import ais.spatial.DistanceBand;
import ais.storage.CommunicationModelRepository;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SimulationPlaybackServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 4);
    private static final CommunicationModelId MODEL_ID =
            CommunicationModelId.parse(
                    "00000000-0000-0000-0000-000000000301");

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsReplaysAndRebuildsTheSameDecisionsAfterBackwardSeek()
            throws Exception {
        Path log = temporaryDirectory.resolve("simulation.ais");
        Files.writeString(log,
                line("20260904090000000", 34.6000)
                        + line("20260904090010000", 34.6002)
                        + line("20260904090040000", 34.6008),
                StandardCharsets.UTF_8);
        HistoricalDaySelection selection = new HistoricalDaySelection(
                DAY, List.of(log), true);
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();

        try (SimulationPlaybackService service =
                     new SimulationPlaybackService(
                             ignored -> receiver,
                             profile,
                             new HistoricalReplayLoader(
                                     ZoneId.of("Asia/Tokyo")),
                             new InMemoryModels(snapshot(profile)))) {
            SimulationFrame loaded = service.load(
                            selection, MODEL_ID, 42L, ignored -> { })
                    .get(5, TimeUnit.SECONDS);
            assertEquals(SimulationPlaybackState.READY, loaded.state());
            assertFalse(loaded.truthStates().isEmpty());

            SimulationFrame firstEnd = service.advance(
                            Duration.ofSeconds(40))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(SimulationPlaybackState.END, firstEnd.state());

            SimulationFrame rewound = service.seek(
                            loaded.startTime().plusSeconds(20))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(SimulationPlaybackState.PAUSED, rewound.state());
            SimulationFrame secondEnd = service.advance(
                            Duration.ofSeconds(20))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(firstEnd.diagnostics(), secondEnd.diagnostics());
            assertEquals(firstEnd.lastDecisions(), secondEnd.lastDecisions());
            assertEquals(firstEnd.truthStates(), secondEnd.truthStates());
            assertEquals(
                    firstEnd.receivedSnapshot().acceptedIntervalCount(),
                    secondEnd.receivedSnapshot().acceptedIntervalCount());
        }
    }

    private static CommunicationModelSnapshot snapshot(
            AnalysisProfile profile) {
        var definition = new CommunicationModelDefinition(
                MODEL_ID, CommunicationModelCode.CM_E1, 1,
                "Playback model", new ReceiverProfileId("receiver"),
                profile.id(), LocalDate.of(2025, 11, 1),
                LocalDate.of(2025, 11, 30), "cm-e1-v1",
                1_000, 42L, Instant.parse("2026-10-06T00:00:00Z"),
                null);
        var parameter = new CommunicationParameter(
                new DistanceBand(0, 0.0, 5.0), VesselClass.CLASS_A,
                50, 50, 10, 10, 0.5, 0.5, 0.5,
                new ConfidenceInterval(0.4, 0.6),
                ParameterApplicability.DIRECT, 0.5, null, null);
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
                CommunicationModelDraft draft,
                String name,
                String notes) {
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
}
