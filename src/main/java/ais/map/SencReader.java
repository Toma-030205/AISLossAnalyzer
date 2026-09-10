package ais.map;

import ais.domain.GeoPosition;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SencReader {

    public MapDataset read(List<Path> files) throws IOException {
        Objects.requireNonNull(files, "files");
        if (files.isEmpty()) {
            throw new IOException("No SENC files were found");
        }
        List<MapFeature> features = new ArrayList<>();
        for (Path file : files) {
            readWithFallbacks(file.toAbsolutePath().normalize(), features);
        }
        if (features.isEmpty()) {
            throw new IOException(
                    "No coastline, land, or river features were found");
        }
        return MapDataset.from(features);
    }

    private static void readWithFallbacks(
            Path file, List<MapFeature> features) throws IOException {
        try {
            read(file, StandardCharsets.UTF_8, features);
        } catch (MalformedInputException first) {
            try {
                read(file, Charset.forName("Windows-31J"), features);
            } catch (MalformedInputException second) {
                read(file, StandardCharsets.ISO_8859_1, features);
            }
        }
    }

    private static void read(Path file, Charset charset,
                             List<MapFeature> features) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String kind = null;
            List<GeoPosition> points = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2
                        && isNumber(parts[0]) && isNumber(parts[1])) {
                    double latitude = Double.parseDouble(parts[0]);
                    double longitude = Double.parseDouble(parts[1]);
                    if (latitude >= -90 && latitude <= 90
                            && longitude >= -180 && longitude <= 180) {
                        points.add(new GeoPosition(latitude, longitude));
                    }
                } else {
                    addFeature(features, kind, points, file);
                    kind = parts[0];
                    points = new ArrayList<>();
                }
            }
            addFeature(features, kind, points, file);
        }
    }

    private static void addFeature(List<MapFeature> features, String kind,
                                   List<GeoPosition> points, Path source) {
        MapFeatureType type = kind == null
                ? null : MapFeatureType.fromSencKind(kind);
        if (type != null && points.size() >= 2) {
            features.add(new MapFeature(
                    type, kind, List.copyOf(points), source));
        }
    }

    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
