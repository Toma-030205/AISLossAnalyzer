package ais.simulation.traffic;

import ais.analysis.CourseChangeTracker;
import ais.analysis.IntervalEvaluation;
import ais.analysis.IntervalEvaluator;
import ais.analysis.IntervalExclusionReason;
import ais.analysis.LossEstimator;
import ais.analysis.MissingPositionEstimator;
import ais.analysis.SpecificationReportRatePolicy;
import ais.app.HistoricalReplayDataset;
import ais.domain.AnalysisProfile;
import ais.domain.NormalizedAisEvent;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.input.history.InputFingerprint;
import ais.spatial.CoordinateProjector;
import ais.spatial.DistanceCalculator;
import ais.spatial.HaversineDistanceCalculator;
import ais.spatial.Utm53NProjector;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class IdealTransmissionGenerator {

    private static final Comparator<PositionReport> REPORT_ORDER =
            Comparator.comparing(PositionReport::receivedAt)
                    .thenComparingLong(PositionReport::sequence);
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparing(Candidate::plannedAt)
                    .thenComparingInt(Candidate::mmsi)
                    .thenComparing(candidate -> candidate.vesselClass().ordinal())
                    .thenComparing(candidate -> candidate.origin()
                            == TransmissionOrigin.OBSERVED_ANCHOR ? 0 : 1)
                    .thenComparingLong(Candidate::tieBreaker);

    private final CoordinateProjector projector;
    private final DistanceCalculator distanceCalculator;

    public IdealTransmissionGenerator() {
        this(new Utm53NProjector(), new HaversineDistanceCalculator());
    }

    public IdealTransmissionGenerator(
            CoordinateProjector projector,
            DistanceCalculator distanceCalculator) {
        this.projector = Objects.requireNonNull(projector, "projector");
        this.distanceCalculator = Objects.requireNonNull(
                distanceCalculator, "distanceCalculator");
    }

    public IdealTransmissionDay generate(
            HistoricalReplayDataset dataset,
            ReceiverProfile receiver,
            AnalysisProfile profile) {
        Objects.requireNonNull(dataset, "dataset");
        return generate(
                dataset.selection().date(),
                dataset.fingerprint(),
                dataset.events(),
                receiver,
                profile);
    }

    public IdealTransmissionDay generate(
            LocalDate inputDate,
            InputFingerprint inputFingerprint,
            List<NormalizedAisEvent> events,
            ReceiverProfile receiver,
            AnalysisProfile profile) {
        Objects.requireNonNull(inputDate, "inputDate");
        Objects.requireNonNull(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(profile, "profile");

        Map<VesselKey, List<PositionReport>> reportsByVessel =
                new HashMap<>();
        List<VesselMetadataUpdate> metadataUpdates = new ArrayList<>();
        for (NormalizedAisEvent event : events) {
            if (event instanceof PositionReport report) {
                VesselKey key = new VesselKey(
                        report.mmsi(), report.vesselClass());
                reportsByVessel.computeIfAbsent(
                        key, ignored -> new ArrayList<>()).add(report);
            } else if (event instanceof VesselMetadataUpdate metadata) {
                metadataUpdates.add(metadata);
            }
        }
        metadataUpdates.sort(Comparator
                .comparing(VesselMetadataUpdate::receivedAt)
                .thenComparingLong(VesselMetadataUpdate::sequence));

        List<Candidate> candidates = new ArrayList<>();
        EnumMap<IntervalExclusionReason, Long> exclusions =
                new EnumMap<>(IntervalExclusionReason.class);
        long[] counters = new long[3];
        long[] tieBreaker = new long[1];

        reportsByVessel.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> generateVessel(
                        entry.getValue(), receiver, profile,
                        candidates, exclusions, counters, tieBreaker));

        candidates.sort(CANDIDATE_ORDER);
        List<IdealTransmission> transmissions = assignIds(
                inputDate, candidates);
        return new IdealTransmissionDay(
                inputDate,
                inputFingerprint,
                transmissions,
                metadataUpdates,
                new IdealTransmissionDiagnostics(
                        counters[0], counters[1], counters[2], exclusions));
    }

    private void generateVessel(
            List<PositionReport> unsortedReports,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            List<Candidate> candidates,
            EnumMap<IntervalExclusionReason, Long> exclusions,
            long[] counters,
            long[] tieBreaker) {
        List<PositionReport> reports = unsortedReports.stream()
                .sorted(REPORT_ORDER)
                .toList();
        SpecificationReportRatePolicy reportRatePolicy =
                new SpecificationReportRatePolicy(profile);
        IntervalEvaluator intervalEvaluator = new IntervalEvaluator(
                receiver, profile, distanceCalculator);
        MissingPositionEstimator positionEstimator =
                new MissingPositionEstimator(projector);
        CourseChangeTracker courseTracker = new CourseChangeTracker();

        PositionReport previous = null;
        double previousExpected = Double.NaN;
        boolean previousChangingCourse = false;

        for (PositionReport current : reports) {
            IntervalEvaluation evaluation = previous == null
                    ? null
                    : intervalEvaluator.evaluate(
                            previous, current, previousExpected);
            if (evaluation instanceof IntervalEvaluation.Excluded excluded) {
                exclusions.merge(excluded.reason(), 1L, Math::addExact);
                courseTracker.reset();
            } else if (evaluation
                    instanceof IntervalEvaluation.Accepted accepted) {
                counters[2] = Math.addExact(counters[2], 1L);
                long missingCount = LossEstimator.estimateMissingMessages(
                        accepted.interval().actualSeconds(),
                        accepted.interval().expectedIntervalSeconds());
                var estimates = positionEstimator.estimate(
                        accepted.interval(), missingCount);
                for (var estimate : estimates) {
                    candidates.add(Candidate.interpolated(
                            previous,
                            previousChangingCourse,
                            estimate.estimatedAt(),
                            estimate.position(),
                            tieBreaker[0]++));
                    counters[1] = Math.addExact(counters[1], 1L);
                }
            }

            boolean changingCourse = courseTracker.accept(current);
            double expected = reportRatePolicy.expectedIntervalSeconds(
                    current, changingCourse);
            candidates.add(Candidate.observed(
                    current, changingCourse, tieBreaker[0]++));
            counters[0] = Math.addExact(counters[0], 1L);
            previous = current;
            previousExpected = expected;
            previousChangingCourse = changingCourse;
        }
    }

    private static List<IdealTransmission> assignIds(
            LocalDate inputDate,
            List<Candidate> candidates) {
        List<IdealTransmission> result = new ArrayList<>(candidates.size());
        IdentityKey previousKey = null;
        int ordinal = 0;
        for (Candidate candidate : candidates) {
            IdentityKey key = new IdentityKey(
                    candidate.mmsi(),
                    candidate.vesselClass(),
                    candidate.plannedAt());
            if (!key.equals(previousKey)) {
                ordinal = 0;
                previousKey = key;
            }
            IdealTransmissionId id = new IdealTransmissionId(
                    inputDate,
                    candidate.mmsi(),
                    candidate.vesselClass(),
                    candidate.plannedAt(),
                    ordinal++);
            result.add(candidate.toTransmission(id));
        }
        return List.copyOf(result);
    }

    private record VesselKey(int mmsi, VesselClass vesselClass)
            implements Comparable<VesselKey> {

        @Override
        public int compareTo(VesselKey other) {
            int mmsiComparison = Integer.compare(mmsi, other.mmsi);
            return mmsiComparison != 0
                    ? mmsiComparison
                    : Integer.compare(vesselClass.ordinal(),
                            other.vesselClass.ordinal());
        }
    }

    private record IdentityKey(
            int mmsi, VesselClass vesselClass, Instant plannedAt) {
    }

    private record Candidate(
            Instant plannedAt,
            int mmsi,
            VesselClass vesselClass,
            int messageType,
            ais.domain.GeoPosition position,
            Double sogKnots,
            Double cogDegrees,
            Double trueHeadingDegrees,
            Integer navigationStatus,
            ais.domain.ClassBReportingMode classBReportingMode,
            boolean assignedMode,
            boolean changingCourse,
            TransmissionOrigin origin,
            long tieBreaker) {

        private static Candidate observed(
                PositionReport report,
                boolean changingCourse,
                long tieBreaker) {
            return from(report, report.receivedAt(), report.position(),
                    changingCourse, TransmissionOrigin.OBSERVED_ANCHOR,
                    tieBreaker);
        }

        private static Candidate interpolated(
                PositionReport source,
                boolean changingCourse,
                Instant plannedAt,
                ais.domain.GeoPosition position,
                long tieBreaker) {
            return from(source, plannedAt, position, changingCourse,
                    TransmissionOrigin.INTERPOLATED, tieBreaker);
        }

        private static Candidate from(
                PositionReport report,
                Instant plannedAt,
                ais.domain.GeoPosition position,
                boolean changingCourse,
                TransmissionOrigin origin,
                long tieBreaker) {
            return new Candidate(
                    plannedAt, report.mmsi(), report.vesselClass(),
                    report.messageType(), position, report.sogKnots(),
                    report.cogDegrees(), report.trueHeadingDegrees(),
                    report.navigationStatus(), report.classBReportingMode(),
                    report.assignedMode(), changingCourse, origin, tieBreaker);
        }

        private IdealTransmission toTransmission(IdealTransmissionId id) {
            return new IdealTransmission(
                    id, messageType, position, sogKnots, cogDegrees,
                    trueHeadingDegrees, navigationStatus,
                    classBReportingMode, assignedMode, changingCourse,
                    origin);
        }
    }
}
