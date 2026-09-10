package ais.domain;

public record ReceiverProfileId(String value) {

    public ReceiverProfileId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "receiver profile id must not be blank");
        }
        value = value.trim();
    }
}
