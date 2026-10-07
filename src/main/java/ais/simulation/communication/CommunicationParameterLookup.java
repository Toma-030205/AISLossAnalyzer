package ais.simulation.communication;

import ais.domain.VesselClass;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.CommunicationParameterKey;
import ais.spatial.DistanceBandDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CommunicationParameterLookup {

    private final DistanceBandDefinition distanceBands;
    private final Map<CommunicationParameterKey, CommunicationParameter>
            parameters;

    public CommunicationParameterLookup(
            DistanceBandDefinition distanceBands,
            List<CommunicationParameter> parameters) {
        this.distanceBands = Objects.requireNonNull(
                distanceBands, "distanceBands");
        Objects.requireNonNull(parameters, "parameters");
        Map<CommunicationParameterKey, CommunicationParameter> indexed =
                new HashMap<>();
        for (CommunicationParameter parameter : parameters) {
            CommunicationParameter previous = indexed.put(
                    parameter.key(), parameter);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate communication parameter: "
                                + parameter.key());
            }
        }
        this.parameters = Map.copyOf(indexed);
    }

    public Optional<CommunicationParameter> find(
            double distanceKilometers,
            VesselClass vesselClass) {
        Objects.requireNonNull(vesselClass, "vesselClass");
        return distanceBands.bandFor(distanceKilometers)
                .map(band -> new CommunicationParameterKey(
                        band.index(), vesselClass))
                .map(parameters::get);
    }

    public double maximumKilometers() {
        return distanceBands.maximumKilometers();
    }
}
