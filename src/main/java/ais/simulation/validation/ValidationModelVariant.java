package ais.simulation.validation;

public enum ValidationModelVariant {
    CM_E1("CM-E1"),
    CLASS_ONLY_BASELINE("距離なし基準");

    private final String label;

    ValidationModelVariant(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
