package ais.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

public final class OsakaBayMap {
    private static final double MAP_WIDTH_RATIO = 2.0 / 3.0;
    private static final List<Path> DEFAULT_FILES = List.of(
            Path.of("C:/Users/Owner/senc/JP34NC8Q.senc"),
            Path.of("C:/Users/Owner/senc/JP34NC8S.senc"),
            Path.of("C:/Users/Owner/senc/JP34NVPQ.senc"));

    private OsakaBayMap() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Options options = Options.parse(args);
        MapPanel mapPanel = createMapPanel(options.inputFiles, options.width, options.height);
        SwingUtilities.invokeLater(() -> showWindow(mapPanel));
    }

    public static MapPanel createDefaultMapPanel() throws IOException {
        return createMapPanel(DEFAULT_FILES, 1200, 900);
    }

    public static MapPanel createMapPanel(List<Path> inputFiles, int width, int height) throws IOException {
        return new MapPanel(SencReader.read(inputFiles), width, height);
    }

    private static void showWindow(MapPanel mapPanel) {
        JFrame frame = new JFrame("Osaka Bay Map");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.add(mapPanel, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static final class MapPanel extends JPanel {
        private final List<Feature> features;

        private MapPanel(List<Feature> features, int width, int height) {
            this.features = List.copyOf(features);
            setPreferredSize(new Dimension(width, height));
            setBackground(MapRenderer.SEA);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                int mapWidth = Math.max(1, (int) Math.round(getWidth() * MAP_WIDTH_RATIO));
                MeridionalPartsProjector projector =
                        MeridionalPartsProjector.from(features, mapWidth, getHeight(), 28.0);
                MapRenderer.render(graphics, getWidth(), getHeight(), features, projector);
            } finally {
                graphics.dispose();
            }
        }
    }

    private record GeoPoint(double lat, double lon) {
    }

    private record ScreenPoint(double x, double y) {
    }

    private record Feature(String kind, List<GeoPoint> points, Path source) {
        boolean isLandArea() {
            return kind.equals("LNDARE_a");
        }

        boolean isCoastline() {
            return kind.equals("COALNE")
                    || kind.equals("LNDARE_l")
                    || kind.equals("SLCONS")
                    || kind.equals("BRIDGE_l");
        }

        boolean isRiver() {
            return kind.equals("RIVERS_l") || kind.equals("RIVERS_a");
        }
    }

    private static final class SencReader {
        static List<Feature> read(List<Path> files) throws IOException {
            List<Feature> features = new ArrayList<>();
            for (Path file : files) {
                readFile(file, features);
            }
            if (features.isEmpty()) {
                throw new IOException("No coordinate features were found.");
            }
            return features;
        }

        private static void readFile(Path file, List<Feature> features) throws IOException {
            try {
                readFile(file, features, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                try {
                    readFile(file, features, Charset.forName("Windows-31J"));
                } catch (MalformedInputException ignored) {
                    readFile(file, features, StandardCharsets.ISO_8859_1);
                }
            }
        }

        private static void readFile(Path file, List<Feature> features, Charset charset) throws IOException {
            try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
                String currentKind = null;
                List<GeoPoint> currentPoints = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && isNumber(parts[0]) && isNumber(parts[1])) {
                        currentPoints.add(new GeoPoint(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])));
                    } else {
                        addFeature(features, currentKind, currentPoints, file);
                        currentKind = parts[0];
                        currentPoints = new ArrayList<>();
                    }
                }
                addFeature(features, currentKind, currentPoints, file);
            }
        }

        private static void addFeature(List<Feature> features, String kind, List<GeoPoint> points, Path source) {
            if (kind != null && points.size() >= 2) {
                features.add(new Feature(kind, List.copyOf(points), source));
            }
        }

        private static boolean isNumber(String text) {
            try {
                Double.parseDouble(text);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    /** WGS-84 ellipsoidal Mercator projection using meridional parts. */
    private static final class MeridionalPartsProjector {
        private static final double WGS84_FLATTENING = 1.0 / 298.257223563;
        private static final double WGS84_ECCENTRICITY =
                Math.sqrt(WGS84_FLATTENING * (2.0 - WGS84_FLATTENING));

        private final double minX;
        private final double maxY;
        private final double scale;
        private final double margin;
        private final double offsetX;
        private final double offsetY;

        private MeridionalPartsProjector(
                double minX, double maxY, double scale, double margin, double offsetX, double offsetY) {
            this.minX = minX;
            this.maxY = maxY;
            this.scale = scale;
            this.margin = margin;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        static MeridionalPartsProjector from(List<Feature> features, int width, int height, double margin) {
            List<Feature> boundsFeatures = features.stream()
                    .filter(feature -> feature.isLandArea() || feature.isCoastline() || feature.isRiver())
                    .toList();
            if (boundsFeatures.isEmpty()) {
                boundsFeatures = features;
            }

            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;

            for (Feature feature : boundsFeatures) {
                for (GeoPoint point : feature.points()) {
                    double x = longitudeMinutes(point.lon());
                    double y = meridionalParts(point.lat());
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }

            double scaleX = (width - margin * 2.0) / (maxX - minX);
            double scaleY = (height - margin * 2.0) / (maxY - minY);
            double scale = Math.min(scaleX, scaleY);
            double mapWidth = (maxX - minX) * scale;
            double mapHeight = (maxY - minY) * scale;
            // Keep the chart against the left side. The unused right third of the
            // panel is reserved for future simulator controls.
            double offsetX = 0.0;
            double offsetY = (height - margin * 2.0 - mapHeight) / 2.0;
            return new MeridionalPartsProjector(minX, maxY, scale, margin, offsetX, offsetY);
        }

        ScreenPoint toScreen(GeoPoint point) {
            double x = margin + offsetX + (longitudeMinutes(point.lon()) - minX) * scale;
            double y = margin + offsetY + (maxY - meridionalParts(point.lat())) * scale;
            return new ScreenPoint(x, y);
        }

        private static double longitudeMinutes(double lon) {
            return lon * 60.0;
        }

        private static double meridionalParts(double lat) {
            double radians = Math.toRadians(lat);
            double eccentricitySinLat = WGS84_ECCENTRICITY * Math.sin(radians);
            double sphericalTerm = Math.log(Math.tan(Math.PI / 4.0 + radians / 2.0));
            double ellipsoidCorrection = WGS84_ECCENTRICITY / 2.0
                    * Math.log((1.0 + eccentricitySinLat) / (1.0 - eccentricitySinLat));
            return Math.toDegrees(sphericalTerm - ellipsoidCorrection) * 60.0;
        }
    }

    private static final class MapRenderer {
        static final Color SEA = new Color(218, 235, 242);
        private static final Color LAND = new Color(226, 224, 211);
        private static final Color LAND_LINE = new Color(88, 101, 88);
        private static final Color COAST = new Color(35, 64, 74);
        private static final Color RIVER = new Color(92, 151, 178, 180);

        static void render(
                Graphics2D g,
                int width,
                int height,
                List<Feature> features,
                MeridionalPartsProjector projector) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(SEA);
            g.fillRect(0, 0, width, height);

            for (Feature feature : features) {
                if (feature.isLandArea()) {
                    Path2D path = toPath(feature, projector);
                    g.setColor(LAND);
                    g.fill(path);
                    g.setColor(LAND_LINE);
                    g.setStroke(new BasicStroke(0.7f));
                    g.draw(path);
                }
            }

            for (Feature feature : features) {
                if (feature.isRiver()) {
                    g.setColor(RIVER);
                    g.setStroke(new BasicStroke(0.8f));
                    g.draw(toPath(feature, projector));
                }
            }

            for (Feature feature : features) {
                if (feature.isCoastline()) {
                    g.setColor(COAST);
                    g.setStroke(new BasicStroke(feature.kind().equals("COALNE") ? 1.5f : 1.0f));
                    g.draw(toPath(feature, projector));
                }
            }

            drawBorder(g, width, height);
        }

        private static Path2D toPath(Feature feature, MeridionalPartsProjector projector) {
            Path2D path = new Path2D.Double();
            boolean first = true;
            for (GeoPoint point : feature.points()) {
                ScreenPoint screen = projector.toScreen(point);
                if (first) {
                    path.moveTo(screen.x(), screen.y());
                    first = false;
                } else {
                    path.lineTo(screen.x(), screen.y());
                }
            }
            if (feature.kind().endsWith("_a")) {
                path.closePath();
            }
            return path;
        }

        private static void drawBorder(Graphics2D g, int width, int height) {
            g.setColor(new Color(42, 52, 56, 120));
            g.drawRect(0, 0, width - 1, height - 1);
        }
    }

    private static final class Options {
        private final List<Path> inputFiles;
        private final int width;
        private final int height;

        private Options(List<Path> inputFiles, int width, int height) {
            this.inputFiles = inputFiles;
            this.width = width;
            this.height = height;
        }

        static Options parse(String[] args) {
            List<Path> inputFiles = new ArrayList<>();
            int width = 1200;
            int height = 900;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--width" -> width = Integer.parseInt(requireValue(args, ++i, "--width"));
                    case "--height" -> height = Integer.parseInt(requireValue(args, ++i, "--height"));
                    case "--help" -> {
                        printUsage();
                        System.exit(0);
                    }
                    default -> inputFiles.add(Path.of(args[i]));
                }
            }

            if (inputFiles.isEmpty()) {
                inputFiles = DEFAULT_FILES;
            }
            return new Options(List.copyOf(inputFiles), width, height);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value.");
            }
            return args[index];
        }

        private static void printUsage() {
            System.out.println("""
                    Usage:
                      java -cp bin ais.ui.OsakaBayMap [options] [senc files...]

                    Options:
                      --width <px>     Image width. Default: 1200.
                      --height <px>    Image height. Default: 900.

                    If no senc files are given, JP34NC8Q, JP34NC8S, and JP34NVPQ
                    are loaded from C:/Users/Owner/senc.
                    """);
        }
    }
}

