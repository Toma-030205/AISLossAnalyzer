package ais.simulation.communication;

import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.traffic.IdealTransmissionId;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

public final class DeterministicUniformSource {

    public double value(
            long seed,
            CommunicationModelId modelId,
            IdealTransmissionId transmissionId) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(transmissionId, "transmissionId");
        UUID modelUuid = modelId.value();
        ByteBuffer input = ByteBuffer.allocate(
                Long.BYTES * 6 + Integer.BYTES * 3);
        input.putLong(seed);
        input.putLong(modelUuid.getMostSignificantBits());
        input.putLong(modelUuid.getLeastSignificantBits());
        input.putLong(transmissionId.inputDate().toEpochDay());
        input.putInt(transmissionId.mmsi());
        input.putInt(transmissionId.vesselClass().ordinal());
        input.putLong(transmissionId.plannedAt().getEpochSecond());
        input.putInt(transmissionId.plannedAt().getNano());
        input.putLong(transmissionId.ordinal());

        byte[] digest = sha256().digest(input.array());
        long fraction = ByteBuffer.wrap(digest).getLong() >>> 11;
        return fraction * 0x1.0p-53;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "this Java runtime does not provide SHA-256",
                    exception);
        }
    }
}
