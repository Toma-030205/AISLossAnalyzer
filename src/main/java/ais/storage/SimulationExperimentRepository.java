package ais.storage;

import ais.simulation.validation.ValidationExperiment;

public interface SimulationExperimentRepository {

    void save(ValidationExperiment experiment);
}
