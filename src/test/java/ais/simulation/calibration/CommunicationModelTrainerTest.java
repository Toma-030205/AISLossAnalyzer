package ais.simulation.calibration;

import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationModelTrainerTest {

    @Test
    void createsDirectInterpolatedAndOutOfModelParameters() {
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        CommunicationTrainingRequest request = request();
        List<CalibrationDayRow> rows = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            LocalDate date = request.startDate().plusDays(day);
            rows.add(row(date, 0, 80, 20));
            rows.add(row(date, 2, 60, 40));
        }
        CalibrationDataset dataset = new CalibrationDataset(
                rows, List.of(AnalysisRunId.create()), List.of());

        CommunicationModelDraft draft = new CommunicationModelTrainer()
                .train(request, receiver(), profile, dataset);

        assertEquals(28, draft.parameters().size());
        CommunicationParameter band0 = parameter(
                draft, 0, VesselClass.CLASS_A);
        CommunicationParameter band1 = parameter(
                draft, 1, VesselClass.CLASS_A);
        CommunicationParameter band3 = parameter(
                draft, 3, VesselClass.CLASS_A);
        CommunicationParameter classB = parameter(
                draft, 1, VesselClass.CLASS_B);

        assertEquals(ParameterApplicability.DIRECT,
                band0.applicability());
        assertEquals((400.0 + 0.5) / (500.0 + 1.0),
                band0.appliedReceptionProbability(), 1.0e-12);
        assertEquals(ParameterApplicability.INTERPOLATED,
                band1.applicability());
        assertEquals(0, band1.lowerSourceBandIndex());
        assertEquals(2, band1.upperSourceBandIndex());
        assertEquals(ParameterApplicability.OUT_OF_MODEL,
                band3.applicability());
        assertNull(band3.appliedReceptionProbability());
        assertEquals(ParameterApplicability.OUT_OF_MODEL,
                classB.applicability());
        assertEquals(2, draft.directParameterCount());
        assertEquals(1, draft.interpolatedParameterCount());
        assertTrue(band0.confidenceInterval().width() < 1.0e-12);
    }

    @Test
    void bootstrapIsReproducibleForTheSameSeed() {
        CommunicationTrainingRequest request = request();
        List<CalibrationDayRow> rows = List.of(
                row(request.startDate(), 0, 90, 10),
                row(request.startDate().plusDays(1), 0, 70, 30),
                row(request.startDate().plusDays(2), 0, 80, 20),
                row(request.startDate().plusDays(3), 0, 60, 40),
                row(request.startDate().plusDays(4), 0, 75, 25));
        DayBlockBootstrap bootstrap = new DayBlockBootstrap();

        ConfidenceInterval first = bootstrap.estimate(rows, 1_000, 42L);
        ConfidenceInterval second = bootstrap.estimate(rows, 1_000, 42L);

        assertEquals(first, second);
        assertTrue(first.width() > 0.0);
    }

    private static CommunicationTrainingRequest request() {
        return new CommunicationTrainingRequest(
                CommunicationModelCode.CM_E1,
                LocalDate.of(2025, 11, 1),
                LocalDate.of(2025, 11, 5),
                Set.of(), new ReceiverProfileId("receiver"),
                AnalysisProfile.phaseOneDefaults().id(), 1_000, 42L);
    }

    private static CalibrationDayRow row(
            LocalDate date, int bandIndex, long observed, long missing) {
        return new CalibrationDayRow(
                date, new DistanceBand(
                        bandIndex, bandIndex * 5.0,
                        (bandIndex + 1.0) * 5.0),
                VesselClass.CLASS_A, observed, missing,
                Set.of(431000001, 431000002, 431000003));
    }

    private static CommunicationParameter parameter(
            CommunicationModelDraft draft,
            int bandIndex,
            VesselClass vesselClass) {
        return draft.parameters().stream()
                .filter(value -> value.distanceBand().index() == bandIndex)
                .filter(value -> value.vesselClass() == vesselClass)
                .findFirst().orElseThrow();
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.7, 135.2), 30.0,
                null, null, LocalDate.of(2020, 1, 1), null, null);
    }
}
