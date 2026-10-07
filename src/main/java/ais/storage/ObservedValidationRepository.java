package ais.storage;

import ais.simulation.validation.ObservedValidationDataset;

public interface ObservedValidationRepository {

    ObservedValidationDataset load(ObservedValidationQuery query);
}
