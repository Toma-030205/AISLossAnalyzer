package ais.export;

import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.simulation.calibration.CalibrationDataset;
import ais.simulation.calibration.CalibrationDayRow;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelTrainer;
import ais.simulation.calibration.CommunicationTrainingRequest;
import ais.spatial.DistanceBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationModelCsvExporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesMetadataAndParameterEvidence() throws Exception {
        LocalDate start = LocalDate.of(2025, 11, 1);
        CommunicationTrainingRequest request =
                new CommunicationTrainingRequest(
                        CommunicationModelCode.CM_E1,
                        start, start.plusDays(4), Set.of(),
                        new ReceiverProfileId("receiver"),
                        AnalysisProfile.phaseOneDefaults().id(), 100, 7L);
        List<CalibrationDayRow> rows = java.util.stream.IntStream.range(0, 5)
                .mapToObj(day -> new CalibrationDayRow(
                        start.plusDays(day), new DistanceBand(0, 0, 5),
                        VesselClass.CLASS_A, 80, 20,
                        Set.of(431000001, 431000002, 431000003)))
                .toList();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.7, 135.2), null,
                null, null, LocalDate.of(2020, 1, 1), null, null);
        CommunicationModelDraft draft = new CommunicationModelTrainer()
                .train(request, receiver, AnalysisProfile.phaseOneDefaults(),
                        new CalibrationDataset(
                                rows, List.of(AnalysisRunId.create()),
                                List.of()));
        Path target = temporaryDirectory.resolve("model.csv");

        new CommunicationModelCsvExporter().write(target, draft);

        String csv = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(csv.contains("# 状態: 保存前の試算"));
        assertTrue(csv.contains("Jeffreys補正受信確率"));
        assertTrue(csv.contains("DIRECT"));
        assertTrue(csv.startsWith("\uFEFF"));
    }
}
