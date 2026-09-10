package ais.domain;

import java.time.LocalDate;
import java.util.Objects;

public record ReceiverProfile(
        ReceiverProfileId id,
        String name,
        GeoPosition position,
        Double antennaHeightMeters,
        String antennaModel,
        String receiverModel,
        LocalDate validFrom,
        LocalDate validTo,
        String notes) {

    public ReceiverProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(validFrom, "validFrom");

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "receiver profile name must not be blank");
        }
        name = name.trim();

        if (antennaHeightMeters != null
                && (!Double.isFinite(antennaHeightMeters)
                || antennaHeightMeters < 0.0)) {
            throw new IllegalArgumentException(
                    "antennaHeightMeters must be finite and zero or greater");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException(
                    "validTo must not be before validFrom");
        }

        antennaModel = normalizeText(antennaModel);
        receiverModel = normalizeText(receiverModel);
        notes = normalizeText(notes);
    }

    public boolean isEffectiveOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(validFrom)
                && (validTo == null || !date.isAfter(validTo));
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
