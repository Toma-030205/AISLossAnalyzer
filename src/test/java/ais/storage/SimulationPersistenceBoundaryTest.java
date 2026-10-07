package ais.storage;

import ais.analysis.DefaultAnalysisEngine;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulationPersistenceBoundaryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void analysisResultStoreRejectsSimulationRuns() {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("simulation.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.68, 135.20), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        Instant startedAt = Instant.parse("2026-10-06T00:00:00Z");
        AnalysisRunId runId = AnalysisRunId.create();
        DefaultAnalysisEngine engine = new DefaultAnalysisEngine();
        engine.begin(new AnalysisContext(
                receiver, profile, SourceMode.SIMULATION,
                runId, startedAt));
        engine.accept(new PositionReport(
                startedAt, 0, 1, 431_000_001,
                receiver.position(), 10.0, 90.0, 90.0, 0,
                ClassBReportingMode.UNKNOWN, false));
        var summary = engine.complete(startedAt);
        AnalysisRun run = new AnalysisRun(
                runId, SourceMode.SIMULATION, null,
                "simulation", null,
                receiver.id(), profile.id(), startedAt);

        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisResultStore(database)
                        .replaceCompletedRun(
                                run, summary, List.of(), List.of()));
    }
}
