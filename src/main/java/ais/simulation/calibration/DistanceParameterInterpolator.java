package ais.simulation.calibration;

import ais.domain.VesselClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class DistanceParameterInterpolator {

    public List<CommunicationParameter> interpolate(
            List<CommunicationParameter> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        List<CommunicationParameter> result = new ArrayList<>();
        for (CommunicationParameter parameter : parameters) {
            if (parameter.applicability()
                    != ParameterApplicability.OUT_OF_MODEL) {
                result.add(parameter);
                continue;
            }
            CommunicationParameter lower = directNeighbor(
                    parameters, parameter.vesselClass(),
                    parameter.distanceBand().index(), true);
            CommunicationParameter upper = directNeighbor(
                    parameters, parameter.vesselClass(),
                    parameter.distanceBand().index(), false);
            if (lower == null || upper == null) {
                result.add(parameter);
                continue;
            }
            double lowerCenter = center(lower);
            double upperCenter = center(upper);
            double targetCenter = center(parameter);
            double weight = (targetCenter - lowerCenter)
                    / (upperCenter - lowerCenter);
            double probability = lower.appliedReceptionProbability()
                    + weight * (upper.appliedReceptionProbability()
                    - lower.appliedReceptionProbability());
            result.add(new CommunicationParameter(
                    parameter.distanceBand(), parameter.vesselClass(),
                    parameter.observedCount(), parameter.missingCount(),
                    parameter.distinctVesselCount(),
                    parameter.observedDayCount(),
                    parameter.rawLossRate(), parameter.rawReceptionRate(),
                    parameter.jeffreysReceptionProbability(),
                    parameter.confidenceInterval(),
                    ParameterApplicability.INTERPOLATED,
                    probability,
                    lower.distanceBand().index(),
                    upper.distanceBand().index()));
        }
        return result.stream()
                .sorted(Comparator
                        .comparingInt((CommunicationParameter value) ->
                                value.distanceBand().index())
                        .thenComparing(CommunicationParameter::vesselClass))
                .toList();
    }

    private static CommunicationParameter directNeighbor(
            List<CommunicationParameter> parameters,
            VesselClass vesselClass,
            int targetIndex,
            boolean lower) {
        return parameters.stream()
                .filter(value -> value.vesselClass() == vesselClass)
                .filter(value -> value.applicability()
                        == ParameterApplicability.DIRECT)
                .filter(value -> lower
                        ? value.distanceBand().index() < targetIndex
                        : value.distanceBand().index() > targetIndex)
                .min(Comparator.comparingInt(value -> Math.abs(
                        value.distanceBand().index() - targetIndex)))
                .orElse(null);
    }

    private static double center(CommunicationParameter parameter) {
        return (parameter.distanceBand().lowerKilometers()
                + parameter.distanceBand().upperKilometers()) / 2.0;
    }
}
