package ais.input.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

final class HistoricalReaders {

    private HistoricalReaders() {
    }

    static BufferedReader open(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(
                openBytes(file),
                StandardCharsets.UTF_8));
    }

    static InputStream openBytes(Path file) throws IOException {
        InputStream input = Files.newInputStream(file);
        if (!file.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT)
                .endsWith(".gz")) {
            return input;
        }

        try {
            return new GZIPInputStream(input);
        } catch (IOException | RuntimeException exception) {
            input.close();
            throw exception;
        }
    }

    static boolean isSupported(Path file) {
        String name = file.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);
        return name.endsWith(".ais") || name.endsWith(".ais.gz");
    }
}
