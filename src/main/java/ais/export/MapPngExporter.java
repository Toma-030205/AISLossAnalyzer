package ais.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.imageio.ImageIO;

public final class MapPngExporter {

    public void write(Path target, BufferedImage image) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(image, "image");
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        if (!ImageIO.write(image, "png", absolute.toFile())) {
            throw new IOException("PNG writer is not available");
        }
    }
}
