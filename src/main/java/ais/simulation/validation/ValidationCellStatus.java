package ais.simulation.validation;

public enum ValidationCellStatus {
    MATCH("一致"),
    REVIEW("要確認"),
    OUT_OF_MODEL("適用外");

    private final String label;

    ValidationCellStatus(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
