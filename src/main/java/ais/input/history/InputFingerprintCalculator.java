package ais.input.history;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class InputFingerprintCalculator {

    public InputFingerprint calculate(List<Path> files) throws IOException {
        Objects.requireNonNull(files, "files");
        if (files.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one input file is required");
        }

        List<Path> ordered = files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(
                        Path::toString,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        MessageDigest combined = sha256Digest();
        long totalBytes = 0;

        for (Path file : ordered) {
            FileFingerprint fingerprint = calculateFile(file);
            combined.update(ByteBuffer.allocate(Long.BYTES)
                    .putLong(fingerprint.uncompressedBytes())
                    .array());
            combined.update(fingerprint.digest());
            totalBytes = Math.addExact(
                    totalBytes,
                    fingerprint.uncompressedBytes());
        }

        return new InputFingerprint(
                HexFormat.of().formatHex(combined.digest()),
                totalBytes,
                ordered.size());
    }

    static FileFingerprint calculateFile(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest = sha256Digest();
        long byteCount = 0;
        byte[] buffer = new byte[64 * 1024];

        try (InputStream input = HistoricalReaders.openBytes(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                    byteCount = Math.addExact(byteCount, count);
                }
            }
        }
        return new FileFingerprint(digest.digest(), byteCount);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "this Java runtime does not provide SHA-256",
                    exception);
        }
    }

    record FileFingerprint(byte[] digest, long uncompressedBytes) {

        FileFingerprint {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        String key() {
            return uncompressedBytes + ":"
                    + HexFormat.of().formatHex(digest);
        }
    }
}
