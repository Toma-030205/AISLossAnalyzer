package ais.simulation.communication;

import ais.domain.AnalysisProfile;
import ais.domain.VesselClass;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.ParameterApplicability;
import ais.simulation.traffic.IdealTransmission;
import ais.spatial.DistanceCalculator;
import ais.spatial.HaversineDistanceCalculator;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ClassOnlyBaselineModel implements CommunicationModel {

    private final CommunicationModelSnapshot snapshot;
    private final Map<VesselClass, Double> receptionProbabilities;
    private final double maximumDistanceKilometers;
    private final DistanceCalculator distanceCalculator;
    private final DeterministicUniformSource uniformSource;

    public ClassOnlyBaselineModel(
            CommunicationModelSnapshot snapshot,
            AnalysisProfile profile) {
        this(snapshot, profile, new HaversineDistanceCalculator(),
                new DeterministicUniformSource());
    }

    public ClassOnlyBaselineModel(
            CommunicationModelSnapshot snapshot,
            AnalysisProfile profile,
            DistanceCalculator distanceCalculator,
            DeterministicUniformSource uniformSource) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(profile, "profile");
        if (!snapshot.definition().analysisProfileId()
                .equals(profile.id())) {
            throw new IllegalArgumentException(
                    "model and analysis profile do not match");
        }
        maximumDistanceKilometers = profile.maximumDistanceKilometers();
        this.distanceCalculator = Objects.requireNonNull(
                distanceCalculator, "distanceCalculator");
        this.uniformSource = Objects.requireNonNull(
                uniformSource, "uniformSource");
        receptionProbabilities = pooledProbabilities(snapshot);
    }

    @Override
    public ReceptionDecision decide(
            IdealTransmission transmission,
            ReceptionContext context) {
        Objects.requireNonNull(transmission, "transmission");
        requireContext(context);
        double distance = distanceCalculator.distanceKilometers(
                context.receiverProfile().position(),
                transmission.position());
        double uniform = uniformSource.value(
                context.seed(), context.modelId(), transmission.id());
        Double probability = receptionProbabilities.get(
                transmission.vesselClass());
        if (distance >= maximumDistanceKilometers || probability == null) {
            return ReceptionDecision.outOfModel(null, uniform, distance);
        }
        ReceptionOutcome outcome = uniform < probability
                ? ReceptionOutcome.RECEIVED
                : ReceptionOutcome.LOST;
        return new ReceptionDecision(
                outcome,
                probability,
                null,
                ParameterApplicability.DIRECT,
                uniform,
                distance);
    }

    private void requireContext(ReceptionContext context) {
        Objects.requireNonNull(context, "context");
        if (!snapshot.definition().id().equals(context.modelId())) {
            throw new IllegalArgumentException(
                    "reception context refers to another model");
        }
        if (!snapshot.definition().receiverProfileId()
                .equals(context.receiverProfile().id())) {
            throw new IllegalArgumentException(
                    "model and receiver profile do not match");
        }
    }

    private static Map<VesselClass, Double> pooledProbabilities(
            CommunicationModelSnapshot snapshot) {
        EnumMap<VesselClass, long[]> counts =
                new EnumMap<>(VesselClass.class);
        Set<ais.simulation.calibration.CommunicationParameterKey> seen =
                new HashSet<>();
        for (CommunicationParameter parameter : snapshot.parameters()) {
            if (!seen.add(parameter.key())) {
                throw new IllegalArgumentException(
                        "duplicate communication parameter: "
                                + parameter.key());
            }
            long[] classCounts = counts.computeIfAbsent(
                    parameter.vesselClass(), ignored -> new long[2]);
            classCounts[0] = Math.addExact(
                    classCounts[0], parameter.observedCount());
            classCounts[1] = Math.addExact(
                    classCounts[1], parameter.missingCount());
        }
        EnumMap<VesselClass, Double> probabilities =
                new EnumMap<>(VesselClass.class);
        counts.forEach((vesselClass, classCounts) -> {
            long expected = Math.addExact(classCounts[0], classCounts[1]);
            if (expected > 0) {
                probabilities.put(
                        vesselClass,
                        (classCounts[0] + 0.5) / (expected + 1.0));
            }
        });
        return Map.copyOf(probabilities);
    }
}
