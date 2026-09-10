package ais.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class SencCatalog {

    public List<Path> scan(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException(
                    "SENC folder is not a directory: " + normalized);
        }
        try (var paths = Files.walk(normalized)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".senc"))
                    .sorted(Comparator.comparing(Path::toString,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }
}
