package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.input.history.HistoricalDaySelection;
import ais.storage.AnalysisResultStore;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcAnalysisRunRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalAnalysisServiceTest {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 4);

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsAdvancesRebuildsBackwardAndSavesFullDay() throws Exception {
        Path log = temporaryDirectory.resolve("day.ais");
        Files.writeString(log,
                line("20260904090000000", 34.6000)
                        + line("20260904090010000", 34.6005)
                        + line("20260904090020000", 34.6010),
                StandardCharsets.UTF_8);
        HistoricalDaySelection selection = new HistoricalDaySelection(
                DAY, List.of(log), true);
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("analysis.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);

        try (HistoricalAnalysisService service =
                     new HistoricalAnalysisService(
                             receiver, profile, JAPAN,
                             new AnalysisResultStore(database))) {
            ReplayFrame loaded = service.load(selection, ignored -> { })
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ReplayState.READY, loaded.state());
            assertEquals(1, loaded.processedEventCount());
            assertEquals(3, loaded.dataset().events().size());

            ReplayFrame end = service.advance(Duration.ofSeconds(20))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ReplayState.END, end.state());
            assertEquals(3, end.processedEventCount());
            assertEquals(2, end.snapshot().acceptedIntervalCount());

            ReplayFrame rewound = service.seek(
                            loaded.displayTime().plusSeconds(10))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ReplayState.PAUSED, rewound.state());
            assertEquals(2, rewound.processedEventCount());
            assertEquals(1, rewound.snapshot().acceptedIntervalCount());

            FullDayAnalysisResult saved = service.analyzeAndSave()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, saved.summary().acceptedIntervalCount());
            var stored = new JdbcAnalysisRunRepository(database)
                    .findEquivalent(DAY, selectionFingerprint(selection),
                            receiver.id(), profile.id());
            assertTrue(stored.isPresent());
            assertEquals(saved.runId(), stored.orElseThrow().run().id());
            assertTrue(stored.orElseThrow().active());
        }
    }

    @Test
    void loaderKeepsDiagnosticsButDoesNotPersistRawLines() throws Exception {
        Path log = temporaryDirectory.resolve("diagnostic.ais");
        Files.writeString(log,
                "not a log line\n" + line(
                        "20260904090000000", 34.6),
                StandardCharsets.UTF_8);
        HistoricalDaySelection selection = new HistoricalDaySelection(
                DAY, List.of(log), true);

        HistoricalReplayDataset dataset = new HistoricalReplayLoader(JAPAN)
                .load(selection, ignored -> { });

        assertEquals(1, dataset.events().size());
        assertFalse(dataset.diagnostics().isEmpty());
        assertEquals(2, dataset.inputRecordCount() +
                dataset.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.code().name()
                                .equals("INVALID_LOG_LINE"))
                        .count());
    }

    private static String line(String timestamp, double latitude) {
        String payload = AisTestData.type1(
                1, 431000001, latitude, 135.2);
        return timestamp + " "
                + AisTestData.sentence(payload, 0) + "\n";
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("lab"), "Laboratory",
                new GeoPosition(34.718983358515715,
                        135.29057866131427),
                null, null, null,
                LocalDate.of(2000, 1, 1), null, null);
    }

    private static ais.input.history.InputFingerprint selectionFingerprint(
            HistoricalDaySelection selection) throws Exception {
        return new ais.input.history.InputFingerprintCalculator()
                .calculate(selection.files());
    }
}
