package ais.simulation.calibration;

public enum CommunicationModelCode {
    CM_E1("CM-E1");

    private final String label;

    CommunicationModelCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
