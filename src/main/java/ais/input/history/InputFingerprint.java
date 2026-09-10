package ais.input.history;

import java.util.Locale;

public record InputFingerprint(
        String sha256,
        long uncompressedBytes,
        int fileCount) {

    public InputFingerprint {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "sha256 must contain 64 hexadecimal characters");
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (uncompressedBytes < 0) {
            throw new IllegalArgumentException(
                    "uncompressedBytes must not be negative");
        }
        if (fileCount <= 0) {
            throw new IllegalArgumentException(
                    "fileCount must be greater than zero");
        }
    }
}
