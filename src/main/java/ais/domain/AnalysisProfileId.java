package ais.domain;

public record AnalysisProfileId(String value) {

    public AnalysisProfileId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "analysis profile id must not be blank");
        }
        value = value.trim();
    }
}
