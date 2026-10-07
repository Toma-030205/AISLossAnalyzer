package ais.simulation.calibration;

import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CommunicationModelTrainer {

    public static final String FORMULA_VERSION =
            "cm-e1-jeffreys-day-bootstrap-v1";

    private final PooledRateEstimator estimator;
    private final DayBlockBootstrap bootstrap;
    private final ParameterEligibilityPolicy eligibility;
    private final DistanceParameterInterpolator interpolator;

    public CommunicationModelTrainer() {
        this(new PooledRateEstimator(), new DayBlockBootstrap(),
                new ParameterEligibilityPolicy(),
                new DistanceParameterInterpolator());
    }

    CommunicationModelTrainer(
            PooledRateEstimator estimator,
            DayBlockBootstrap bootstrap,
            ParameterEligibilityPolicy eligibility,
            DistanceParameterInterpolator interpolator) {
        this.estimator = estimator;
        this.bootstrap = bootstrap;
        this.eligibility = eligibility;
        this.interpolator = interpolator;
    }

    public CommunicationModelDraft train(
            CommunicationTrainingRequest request,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            CalibrationDataset dataset) {
        Map<CommunicationParameterKey, List<CalibrationDayRow>> grouped =
                new HashMap<>();
        for (CalibrationDayRow row : dataset.dailyRows()) {
            CommunicationParameterKey key = new CommunicationParameterKey(
                    row.distanceBand().index(), row.vesselClass());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(row);
        }

        List<CommunicationParameter> initial = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!dataset.missingDates().isEmpty()) {
            warnings.add("未解析日: " + dataset.missingDates());
        }
        int bandCount = (int) Math.ceil(
                profile.maximumDistanceKilometers()
                        / (double) profile.distanceBinKilometers());
        for (int bandIndex = 0; bandIndex < bandCount; bandIndex++) {
            DistanceBand band = new DistanceBand(
                    bandIndex,
                    bandIndex * (double) profile.distanceBinKilometers(),
                    Math.min(profile.maximumDistanceKilometers(),
                            (bandIndex + 1.0)
                                    * profile.distanceBinKilometers()));
            for (VesselClass vesselClass : List.of(
                    VesselClass.CLASS_A, VesselClass.CLASS_B)) {
                CommunicationParameterKey key =
                        new CommunicationParameterKey(
                                bandIndex, vesselClass);
                List<CalibrationDayRow> rows = grouped.getOrDefault(
                        key, List.of());
                PooledEstimate pooled = estimator.estimate(rows);
                ConfidenceInterval interval = bootstrap.estimate(
                        rows, request.bootstrapIterations(),
                        cellSeed(request.bootstrapSeed(), key));
                List<String> reasons = eligibility.rejectionReasons(
                        pooled, interval, profile);
                boolean direct = reasons.isEmpty();
                initial.add(new CommunicationParameter(
                        band, vesselClass,
                        pooled.observedCount(), pooled.missingCount(),
                        pooled.distinctVesselCount(),
                        pooled.observedDayCount(), pooled.rawLossRate(),
                        pooled.rawReceptionRate(),
                        pooled.jeffreysReceptionProbability(), interval,
                        direct ? ParameterApplicability.DIRECT
                                : ParameterApplicability.OUT_OF_MODEL,
                        direct ? pooled.jeffreysReceptionProbability()
                                : null,
                        null, null));
                if (!direct) {
                    warnings.add(band.label() + " / "
                            + classLabel(vesselClass) + ": "
                            + String.join("、", reasons));
                }
            }
        }

        List<CommunicationParameter> parameters =
                interpolator.interpolate(initial);
        long interpolated = parameters.stream().filter(parameter ->
                parameter.applicability()
                        == ParameterApplicability.INTERPOLATED).count();
        long out = parameters.stream().filter(parameter ->
                parameter.applicability()
                        == ParameterApplicability.OUT_OF_MODEL).count();
        if (interpolated > 0) {
            warnings.add("データ不足セル" + interpolated
                    + "件を同一Classの両側直接値から補間しました");
        }
        if (out > 0) {
            warnings.add("適用外セルが" + out + "件あります");
        }
        return new CommunicationModelDraft(
                request, receiver, profile, FORMULA_VERSION,
                parameters, dataset.sourceRunIds(),
                dataset.missingDates(), warnings, Instant.now());
    }

    private static long cellSeed(
            long seed, CommunicationParameterKey key) {
        long mixed = seed ^ 0x9E3779B97F4A7C15L;
        mixed ^= (long) key.distanceBandIndex() * 0xBF58476D1CE4E5B9L;
        mixed ^= (long) key.vesselClass().ordinal()
                * 0x94D049BB133111EBL;
        return mixed;
    }

    private static String classLabel(VesselClass vesselClass) {
        return vesselClass == VesselClass.CLASS_A ? "Class A" : "Class B";
    }
}
