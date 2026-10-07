package ais.simulation.communication;

import ais.simulation.traffic.IdealTransmission;

public interface CommunicationModel {

    ReceptionDecision decide(
            IdealTransmission transmission,
            ReceptionContext context);
}
