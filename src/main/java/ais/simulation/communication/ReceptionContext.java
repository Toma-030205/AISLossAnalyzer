package ais.simulation.communication;

import ais.domain.ReceiverProfile;
import ais.simulation.calibration.CommunicationModelId;

import java.util.Objects;

public record ReceptionContext(
        ReceiverProfile receiverProfile,
        CommunicationModelId modelId,
        long seed) {

    public ReceptionContext {
        Objects.requireNonNull(receiverProfile, "receiverProfile");
        Objects.requireNonNull(modelId, "modelId");
    }
}
