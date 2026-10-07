package ais.storage;

import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.CommunicationModelSnapshot;

import java.util.List;
import java.util.Optional;

public interface CommunicationModelRepository {

    CommunicationModelDefinition save(
            CommunicationModelDraft draft,
            String name,
            String notes);

    List<CommunicationModelDefinition> findAll();

    Optional<CommunicationModelSnapshot> findById(
            CommunicationModelId modelId);
}
