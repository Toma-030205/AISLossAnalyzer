package ais.storage;

import ais.simulation.calibration.CalibrationDataset;
import ais.simulation.calibration.CommunicationTrainingRequest;

public interface CommunicationCalibrationRepository {

    CalibrationDataset load(CommunicationTrainingRequest request);
}
