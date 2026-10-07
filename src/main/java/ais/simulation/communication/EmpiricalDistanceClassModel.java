package ais.simulation.communication;

import ais.domain.AnalysisProfile;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.ParameterApplicability;
import ais.simulation.traffic.IdealTransmission;
import ais.spatial.DistanceBandDefinition;
import ais.spatial.DistanceCalculator;
import ais.spatial.HaversineDistanceCalculator;

import java.util.Objects;

public final class EmpiricalDistanceClassModel
        implements CommunicationModel {

    private final CommunicationModelSnapshot snapshot;
    private final CommunicationParameterLookup parameterLookup;
    private final DistanceCalculator distanceCalculator;
    private final DeterministicUniformSource uniformSource;

    public EmpiricalDistanceClassModel(
            CommunicationModelSnapshot snapshot,
            AnalysisProfile profile) {
        this(snapshot, profile, new HaversineDistanceCalculator(),
                new DeterministicUniformSource());
    }

    public EmpiricalDistanceClassModel(
            CommunicationModelSnapshot snapshot,
            AnalysisProfile profile,
            DistanceCalculator distanceCalculator,
            DeterministicUniformSource uniformSource) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(profile, "profile");
        if (snapshot.definition().modelCode()
                != CommunicationModelCode.CM_E1) {
            throw new IllegalArgumentException("CM-E1 model is required");
        }
        if (!snapshot.definition().analysisProfileId()
                .equals(profile.id())) {
            throw new IllegalArgumentException(
                    "model and analysis profile do not match");
        }
        parameterLookup = new CommunicationParameterLookup(
                new DistanceBandDefinition(
                        profile.distanceBinKilometers(),
                        profile.maximumDistanceKilometers()),
                snapshot.parameters());
        this.distanceCalculator = Objects.requireNonNull(
                distanceCalculator, "distanceCalculator");
        this.uniformSource = Objects.requireNonNull(
                uniformSource, "uniformSource");
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
        if (distance >= parameterLookup.maximumKilometers()) {
            return ReceptionDecision.outOfModel(null, uniform, distance);
        }
        CommunicationParameter parameter = parameterLookup.find(
                        distance, transmission.vesselClass())
                .orElse(null);
        if (parameter == null
                || parameter.applicability()
                        == ParameterApplicability.OUT_OF_MODEL
                || parameter.appliedReceptionProbability() == null) {
            return ReceptionDecision.outOfModel(
                    parameter == null ? null : parameter.key(),
                    uniform,
                    distance);
        }
        double probability = parameter.appliedReceptionProbability();
        ReceptionOutcome outcome = uniform < probability
                ? ReceptionOutcome.RECEIVED
                : ReceptionOutcome.LOST;
        return new ReceptionDecision(
                outcome,
                probability,
                parameter.key(),
                parameter.applicability(),
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
}
