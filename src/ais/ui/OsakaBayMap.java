package ais.ui;

import ais.model.AisMessage;
import ais.parser.FileLoader;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

public final class OsakaBayMap {
    private static final double MAP_WIDTH_RATIO = 2.0 / 3.0;
    private static final List<Path> DEFAULT_FILES = List.of(
            Path.of("C:/Users/Owner/senc/JP34NC8Q.senc"),
            Path.of("C:/Users/Owner/senc/JP34NC8S.senc"),
            Path.of("C:/Users/Owner/senc/JP34NVPQ.senc"));
    private static final Path DEFAULT_AIS_DATA_DIR = Path.of("C:/Users/Owner/AISData");
    private static final int DEFAULT_AIS_FILE_LIMIT = 1;

    private OsakaBayMap() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Options options = Options.parse(args);
        MapPanel mapPanel = createMapPanel(
                options.inputFiles,
                options.width,
                options.height,
                options.aisDataDir,
                options.aisFileLimit);
        SwingUtilities.invokeLater(() -> showWindow(mapPanel));
    }

    public static MapPanel createDefaultMapPanel() throws IOException {
        return createMapPanel(DEFAULT_FILES, 1200, 900, DEFAULT_AIS_DATA_DIR, DEFAULT_AIS_FILE_LIMIT);
    }

    public static MapPanel createMapPanel(List<Path> inputFiles, int width, int height) throws IOException {
        return createMapPanel(inputFiles, width, height, DEFAULT_AIS_DATA_DIR, DEFAULT_AIS_FILE_LIMIT);
    }

    public static MapPanel createMapPanel(
            List<Path> inputFiles, int width, int height, Path aisDataDir, int aisFileLimit) throws IOException {
        return new MapPanel(
                SencReader.read(inputFiles),
                AisTimeline.load(aisDataDir, aisFileLimit),
                width,
                height);
    }

    private static void showWindow(MapPanel mapPanel) {
        JFrame frame = new JFrame("Osaka Bay Map");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.add(mapPanel);
        frame.setResizable(false);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static final class MapPanel extends JPanel {
        private static final double MIN_ZOOM = 0.4;
        private static final double MAX_ZOOM = 8.0;
        private static final double ZOOM_STEP = 1.18;
        private static final int PLAYBACK_TIMER_DELAY_MS = 250;
        private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        private final List<Feature> features;
        private final AisTimeline timeline;
        private final JPanel controlsPanel;
        private JLabel zoomLabel;
        private JLabel timeLabel;
        private JLabel shipCountLabel;
        private JLabel dataLabel;
        private JSlider timeSlider;
        private JButton playButton;
        private JComboBox<SpeedOption> speedCombo;
        private Timer playbackTimer;
        private List<ShipState> visibleShips = List.of();
        private double currentOffsetSeconds = 0.0;
        private double zoom = 1.0;
        private double panX = 0.0;
        private double panY = 0.0;
        private Point lastDragPoint;

        private MapPanel(List<Feature> features, AisTimeline timeline, int width, int height) {
            this.features = List.copyOf(features);
            this.timeline = timeline;
            Dimension size = new Dimension(width, height);
            setMinimumSize(size);
            setPreferredSize(size);
            setMaximumSize(size);
            setBackground(MapRenderer.SEA);
            setLayout(null);
            controlsPanel = createZoomControls();
            add(controlsPanel);
            addMouseWheelListener(this::handleMouseWheel);
            DragHandler dragHandler = new DragHandler();
            addMouseListener(dragHandler);
            addMouseMotionListener(dragHandler);
            initializeTimelineControls();
            updateZoomLabel();
        }

        @Override
        public void doLayout() {
            int mapWidth = getMapViewportWidth();
            controlsPanel.setBounds(mapWidth, 0, Math.max(0, getWidth() - mapWidth), getHeight());
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            Graphics2D graphics = (Graphics2D) g.create();
            try {
                int mapWidth = getMapViewportWidth();
                MeridionalPartsProjector projector =
                        MeridionalPartsProjector.from(features, mapWidth, getHeight(), 28.0, zoom, panX, panY);
                MapRenderer.render(graphics, getWidth(), getHeight(), mapWidth, features, visibleShips, projector);
            } finally {
                graphics.dispose();
            }
        }

        private JPanel createZoomControls() {
            JPanel panel = new JPanel();
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(28, 20, 28, 20));

            JPanel buttonRow = new JPanel();
            buttonRow.setOpaque(false);
            buttonRow.setLayout(new BoxLayout(buttonRow, BoxLayout.X_AXIS));
            buttonRow.setAlignmentX(Component.CENTER_ALIGNMENT);

            JButton zoomInButton = createZoomButton("+", "Zoom in");
            JButton zoomOutButton = createZoomButton("-", "Zoom out");
            JButton resetButton = createZoomButton("100%", "Reset zoom");
            zoomLabel = new JLabel();
            zoomLabel.setHorizontalAlignment(SwingConstants.CENTER);
            zoomLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            zoomLabel.setMaximumSize(new Dimension(96, 24));

            zoomInButton.addActionListener(event -> zoomAroundMapCenter(ZOOM_STEP));
            zoomOutButton.addActionListener(event -> zoomAroundMapCenter(1.0 / ZOOM_STEP));
            resetButton.addActionListener(event -> resetView());

            buttonRow.add(zoomInButton);
            buttonRow.add(Box.createHorizontalStrut(8));
            buttonRow.add(zoomOutButton);
            buttonRow.add(Box.createHorizontalStrut(8));
            buttonRow.add(resetButton);

            JLabel mapLabel = createControlLabel("Map");
            panel.add(mapLabel);
            panel.add(Box.createVerticalStrut(8));
            panel.add(buttonRow);
            panel.add(Box.createVerticalStrut(10));
            panel.add(zoomLabel);
            panel.add(Box.createVerticalStrut(28));
            panel.add(createTimelineControls());
            panel.add(Box.createVerticalGlue());
            return panel;
        }

        private JPanel createTimelineControls() {
            JPanel panel = new JPanel();
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setAlignmentX(Component.CENTER_ALIGNMENT);

            timeLabel = createControlLabel("No AIS data");
            shipCountLabel = createControlLabel("Ships: 0");
            dataLabel = createControlLabel("");

            timeSlider = new JSlider();
            timeSlider.setOpaque(false);
            timeSlider.setAlignmentX(Component.CENTER_ALIGNMENT);
            timeSlider.setMaximumSize(new Dimension(260, 44));
            timeSlider.addChangeListener(event -> {
                setCurrentOffsetSeconds(timeSlider.getValue());
            });

            JPanel playbackRow = new JPanel();
            playbackRow.setOpaque(false);
            playbackRow.setLayout(new BoxLayout(playbackRow, BoxLayout.X_AXIS));
            playbackRow.setAlignmentX(Component.CENTER_ALIGNMENT);

            playButton = createZoomButton("Play", "Play timeline");
            playButton.addActionListener(event -> togglePlayback());
            speedCombo = new JComboBox<>(SpeedOption.defaults());
            speedCombo.setFocusable(false);
            speedCombo.setMaximumSize(new Dimension(82, 34));

            playbackRow.add(playButton);
            playbackRow.add(Box.createHorizontalStrut(8));
            playbackRow.add(speedCombo);

            playbackTimer = new Timer(PLAYBACK_TIMER_DELAY_MS, event -> advancePlayback());

            panel.add(createControlLabel("Time"));
            panel.add(Box.createVerticalStrut(8));
            panel.add(timeSlider);
            panel.add(Box.createVerticalStrut(8));
            panel.add(timeLabel);
            panel.add(Box.createVerticalStrut(6));
            panel.add(shipCountLabel);
            panel.add(Box.createVerticalStrut(6));
            panel.add(dataLabel);
            panel.add(Box.createVerticalStrut(12));
            panel.add(playbackRow);
            return panel;
        }

        private JLabel createControlLabel(String text) {
            JLabel label = new JLabel(text);
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
            label.setMaximumSize(new Dimension(280, 24));
            return label;
        }

        private JButton createZoomButton(String text, String tooltip) {
            JButton button = new JButton(text);
            button.setToolTipText(tooltip);
            button.setFocusable(false);
            button.setAlignmentX(Component.CENTER_ALIGNMENT);
            int width = switch (text) {
                case "+", "-" -> 42;
                case "100%" -> 64;
                default -> 72;
            };
            Dimension size = new Dimension(width, 34);
            button.setMinimumSize(size);
            button.setPreferredSize(size);
            button.setMaximumSize(size);
            return button;
        }

        private void initializeTimelineControls() {
            if (timeline.isEmpty()) {
                timeSlider.setMinimum(0);
                timeSlider.setMaximum(0);
                timeSlider.setEnabled(false);
                playButton.setEnabled(false);
                speedCombo.setEnabled(false);
                dataLabel.setText("AIS files: 0");
                return;
            }

            int durationSeconds = timeline.durationSeconds();
            timeSlider.setMinimum(0);
            timeSlider.setMaximum(durationSeconds);
            timeSlider.setValue(0);
            dataLabel.setText(String.format(Locale.ROOT, "AIS files: %d, reports: %d",
                    timeline.fileCount(), timeline.reportCount()));
            setCurrentOffsetSeconds(0.0);
        }

        private void setCurrentOffsetSeconds(double offsetSeconds) {
            if (timeline.isEmpty()) {
                return;
            }
            currentOffsetSeconds = Math.max(0.0, Math.min(timeline.durationSeconds(), offsetSeconds));
            int sliderValue = (int) Math.round(currentOffsetSeconds);
            if (timeSlider.getValue() != sliderValue) {
                timeSlider.setValue(sliderValue);
            }
            LocalDateTime currentTime = timeline.timeAtOffset(currentOffsetSeconds);
            visibleShips = timeline.shipsAt(currentTime);
            timeLabel.setText(TIME_FORMATTER.format(currentTime));
            shipCountLabel.setText(String.format(Locale.ROOT, "Ships: %d", visibleShips.size()));
            repaint();
        }

        private void togglePlayback() {
            if (playbackTimer.isRunning()) {
                playbackTimer.stop();
                playButton.setText("Play");
            } else {
                playbackTimer.start();
                playButton.setText("Pause");
            }
        }

        private void advancePlayback() {
            SpeedOption speed = (SpeedOption) speedCombo.getSelectedItem();
            double secondsPerSecond = speed == null ? 1.0 : speed.secondsPerSecond();
            double nextOffset = currentOffsetSeconds + secondsPerSecond * PLAYBACK_TIMER_DELAY_MS / 1000.0;
            if (nextOffset >= timeline.durationSeconds()) {
                setCurrentOffsetSeconds(timeline.durationSeconds());
                playbackTimer.stop();
                playButton.setText("Play");
                return;
            }
            setCurrentOffsetSeconds(nextOffset);
        }

        private void handleMouseWheel(MouseWheelEvent event) {
            if (event.getX() > getMapViewportWidth()) {
                return;
            }
            double factor = Math.pow(ZOOM_STEP, -event.getPreciseWheelRotation());
            zoomAround(factor, event.getX(), event.getY());
            event.consume();
        }

        private void zoomAroundMapCenter(double factor) {
            zoomAround(factor, getMapViewportWidth() / 2.0, getHeight() / 2.0);
        }

        private void zoomAround(double factor, double anchorX, double anchorY) {
            double oldZoom = zoom;
            double newZoom = clampZoom(zoom * factor);
            if (newZoom == oldZoom) {
                return;
            }
            double centerX = getMapViewportWidth() / 2.0;
            double centerY = getHeight() / 2.0;
            double ratio = newZoom / oldZoom;
            panX = anchorX - centerX - (anchorX - centerX - panX) * ratio;
            panY = anchorY - centerY - (anchorY - centerY - panY) * ratio;
            zoom = newZoom;
            updateZoomLabel();
            repaint();
        }

        private void resetView() {
            zoom = 1.0;
            panX = 0.0;
            panY = 0.0;
            updateZoomLabel();
            repaint();
        }

        private double clampZoom(double newZoom) {
            return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        }

        private void updateZoomLabel() {
            if (zoomLabel != null) {
                zoomLabel.setText(String.format(Locale.ROOT, "%.0f%%", zoom * 100.0));
            }
        }

        private int getMapViewportWidth() {
            return Math.max(1, (int) Math.round(getWidth() * MAP_WIDTH_RATIO));
        }

        private boolean isInsideMapViewport(MouseEvent event) {
            return event.getX() >= 0 && event.getX() <= getMapViewportWidth()
                    && event.getY() >= 0 && event.getY() <= getHeight();
        }

        private final class DragHandler extends MouseAdapter {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && isInsideMapViewport(event)) {
                    lastDragPoint = event.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (lastDragPoint == null) {
                    return;
                }
                Point point = event.getPoint();
                panX += point.x - lastDragPoint.x;
                panY += point.y - lastDragPoint.y;
                lastDragPoint = point;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                stopDrag();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if ((event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) {
                    stopDrag();
                }
            }

            private void stopDrag() {
                lastDragPoint = null;
                setCursor(Cursor.getDefaultCursor());
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

    private record ShipReport(
            LocalDateTime timestamp,
            int mmsi,
            int messageType,
            double lat,
            double lon,
            Double sog,
            Double cog,
            Double trueHeading) {
    }

    private record ShipState(
            int mmsi,
            int messageType,
            double lat,
            double lon,
            Double sog,
            Double cog,
            Double trueHeading,
            Integer shipLength,
            String vesselName) {
        double headingDegrees() {
            if (trueHeading != null) {
                return trueHeading;
            }
            if (cog != null) {
                return cog;
            }
            return 0.0;
        }
    }

    private record SpeedOption(String label, double secondsPerSecond) {
        static SpeedOption[] defaults() {
            return new SpeedOption[] {
                    new SpeedOption("1x", 1.0),
                    new SpeedOption("5x", 5.0),
                    new SpeedOption("10x", 10.0),
                    new SpeedOption("30x", 30.0),
                    new SpeedOption("60x", 60.0),
                    new SpeedOption("300x", 300.0)
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class AisTimeline {
        private static final Duration ACTIVE_WINDOW = Duration.ofMinutes(10);

        private final List<ShipReport> reports;
        private final Map<Integer, ShipMetadata> metadataByMmsi;
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final int fileCount;
        private final int durationSeconds;

        private AisTimeline(
                List<ShipReport> reports,
                Map<Integer, ShipMetadata> metadataByMmsi,
                LocalDateTime startTime,
                LocalDateTime endTime,
                int fileCount) {
            this.reports = List.copyOf(reports);
            this.metadataByMmsi = Map.copyOf(metadataByMmsi);
            this.startTime = startTime;
            this.endTime = endTime;
            this.fileCount = fileCount;
            this.durationSeconds = startTime == null || endTime == null
                    ? 0
                    : (int) Math.min(Integer.MAX_VALUE, Math.max(1L, Duration.between(startTime, endTime).getSeconds()));
        }

        static AisTimeline empty() {
            return new AisTimeline(List.of(), Map.of(), null, null, 0);
        }

        static AisTimeline load(Path dataDir, int fileLimit) {
            File[] dataFiles = dataDir.toFile().listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".ais"));
            if (dataFiles == null || dataFiles.length == 0) {
                System.out.println("AIS files not found: " + dataDir);
                return empty();
            }

            Arrays.sort(dataFiles, Comparator.comparing(File::getName));
            int limit = fileLimit <= 0 ? dataFiles.length : Math.min(fileLimit, dataFiles.length);
            List<ShipReport> reports = new ArrayList<>();
            Map<Integer, ShipMetadata> metadata = new HashMap<>();
            FileLoader loader = new FileLoader();

            for (int i = 0; i < limit; i++) {
                File file = dataFiles[i];
                System.out.println("Loading AIS simulation data: " + file.getName());
                loader.loadFile(file.getAbsolutePath(), message -> acceptMessage(message, reports, metadata));
            }

            reports.sort(Comparator.comparing(ShipReport::timestamp));
            if (reports.isEmpty()) {
                return new AisTimeline(List.of(), metadata, null, null, limit);
            }
            return new AisTimeline(
                    reports,
                    metadata,
                    reports.get(0).timestamp(),
                    reports.get(reports.size() - 1).timestamp(),
                    limit);
        }

        private static void acceptMessage(
                AisMessage message, List<ShipReport> reports, Map<Integer, ShipMetadata> metadata) {
            if (message.timestamp == null) {
                return;
            }
            if (message.messageType == 5) {
                ShipMetadata current = metadata.getOrDefault(message.mmsi, ShipMetadata.empty());
                metadata.put(message.mmsi, current.with(message));
                return;
            }
            if (!isDynamicPositionMessage(message) || message.lat == null || message.lon == null) {
                return;
            }
            reports.add(new ShipReport(
                    message.timestamp,
                    message.mmsi,
                    message.messageType,
                    message.lat,
                    message.lon,
                    message.sog,
                    message.cog,
                    message.trueHeading));
        }

        private static boolean isDynamicPositionMessage(AisMessage message) {
            return message.messageType == 1
                    || message.messageType == 2
                    || message.messageType == 3
                    || message.messageType == 18;
        }

        boolean isEmpty() {
            return reports.isEmpty();
        }

        int durationSeconds() {
            return durationSeconds;
        }

        int fileCount() {
            return fileCount;
        }

        int reportCount() {
            return reports.size();
        }

        LocalDateTime timeAtOffset(double offsetSeconds) {
            long nanos = (long) (Math.max(0.0, Math.min(durationSeconds, offsetSeconds)) * 1_000_000_000L);
            return startTime.plusNanos(nanos);
        }

        List<ShipState> shipsAt(LocalDateTime time) {
            int end = upperBound(time);
            LocalDateTime earliest = time.minus(ACTIVE_WINDOW);
            Set<Integer> seen = new HashSet<>();
            List<ShipState> ships = new ArrayList<>();

            for (int i = end - 1; i >= 0; i--) {
                ShipReport report = reports.get(i);
                if (report.timestamp().isBefore(earliest)) {
                    break;
                }
                if (!seen.add(report.mmsi())) {
                    continue;
                }
                ShipMetadata metadata = metadataByMmsi.getOrDefault(report.mmsi(), ShipMetadata.empty());
                ships.add(new ShipState(
                        report.mmsi(),
                        report.messageType(),
                        report.lat(),
                        report.lon(),
                        report.sog(),
                        report.cog(),
                        report.trueHeading(),
                        metadata.shipLength(),
                        metadata.vesselName()));
            }
            return ships;
        }

        private int upperBound(LocalDateTime time) {
            int low = 0;
            int high = reports.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (!reports.get(mid).timestamp().isAfter(time)) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }

    private record ShipMetadata(Integer shipLength, String vesselName) {
        static ShipMetadata empty() {
            return new ShipMetadata(null, null);
        }

        ShipMetadata with(AisMessage message) {
            Integer nextLength = shipLength;
            String nextName = vesselName;
            if (message.shipLength != null && message.shipLength > 0) {
                nextLength = message.shipLength;
            }
            if (message.vesselName != null && !message.vesselName.isBlank()) {
                nextName = message.vesselName;
            }
            return new ShipMetadata(nextLength, nextName);
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
        private final double zoom;
        private final double panX;
        private final double panY;
        private final double viewportCenterX;
        private final double viewportCenterY;

        private MeridionalPartsProjector(
                double minX,
                double maxY,
                double scale,
                double margin,
                double offsetX,
                double offsetY,
                double zoom,
                double panX,
                double panY,
                double viewportCenterX,
                double viewportCenterY) {
            this.minX = minX;
            this.maxY = maxY;
            this.scale = scale;
            this.margin = margin;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.zoom = zoom;
            this.panX = panX;
            this.panY = panY;
            this.viewportCenterX = viewportCenterX;
            this.viewportCenterY = viewportCenterY;
        }

        static MeridionalPartsProjector from(
                List<Feature> features, int width, int height, double margin, double zoom, double panX, double panY) {
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

            double drawableWidth = Math.max(1.0, width - margin * 2.0);
            double drawableHeight = Math.max(1.0, height - margin * 2.0);
            double scaleX = drawableWidth / (maxX - minX);
            double scaleY = drawableHeight / (maxY - minY);
            double scale = Math.min(scaleX, scaleY);
            double mapWidth = (maxX - minX) * scale;
            double mapHeight = (maxY - minY) * scale;
            double offsetX = 0.0;
            double offsetY = (drawableHeight - mapHeight) / 2.0;
            return new MeridionalPartsProjector(
                    minX,
                    maxY,
                    scale,
                    margin,
                    offsetX,
                    offsetY,
                    zoom,
                    panX,
                    panY,
                    width / 2.0,
                    height / 2.0);
        }

        ScreenPoint toScreen(GeoPoint point) {
            double baseX = margin + offsetX + (longitudeMinutes(point.lon()) - minX) * scale;
            double baseY = margin + offsetY + (maxY - meridionalParts(point.lat())) * scale;
            double x = viewportCenterX + (baseX - viewportCenterX) * zoom + panX;
            double y = viewportCenterY + (baseY - viewportCenterY) * zoom + panY;
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
        private static final Color CLASS_A_SHIP = new Color(38, 118, 89, 220);
        private static final Color CLASS_B_SHIP = new Color(36, 94, 154, 220);
        private static final Color SHIP_LINE = new Color(18, 35, 42, 230);

        static void render(
                Graphics2D g,
                int width,
                int height,
                int mapViewportWidth,
                List<Feature> features,
                List<ShipState> ships,
                MeridionalPartsProjector projector) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(SEA);
            g.fillRect(0, 0, width, height);

            java.awt.Shape oldClip = g.getClip();
            g.setClip(0, 0, mapViewportWidth, height);
            try {
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

                drawShips(g, ships, projector);
            } finally {
                g.setClip(oldClip);
            }

            drawBorder(g, width, height);
        }

        private static void drawShips(Graphics2D g, List<ShipState> ships, MeridionalPartsProjector projector) {
            for (ShipState ship : ships) {
                ScreenPoint screen = projector.toScreen(new GeoPoint(ship.lat(), ship.lon()));
                double heading = ship.headingDegrees();
                double iconLength = shipIconLength(ship.shipLength());
                double iconWidth = Math.max(6.0, iconLength * 0.58);
                double angle = Math.toRadians(heading - 90.0);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double sideX = -sin;
                double sideY = cos;

                double tipX = screen.x() + cos * iconLength;
                double tipY = screen.y() + sin * iconLength;
                double sternX = screen.x() - cos * iconLength * 0.62;
                double sternY = screen.y() - sin * iconLength * 0.62;

                Path2D triangle = new Path2D.Double();
                triangle.moveTo(tipX, tipY);
                triangle.lineTo(sternX + sideX * iconWidth / 2.0, sternY + sideY * iconWidth / 2.0);
                triangle.lineTo(sternX - sideX * iconWidth / 2.0, sternY - sideY * iconWidth / 2.0);
                triangle.closePath();

                g.setColor(ship.messageType() == 18 ? CLASS_B_SHIP : CLASS_A_SHIP);
                g.fill(triangle);
                g.setColor(SHIP_LINE);
                g.setStroke(new BasicStroke(0.9f));
                g.draw(triangle);
            }
        }

        private static double shipIconLength(Integer shipLength) {
            if (shipLength == null || shipLength <= 0) {
                return 10.0;
            }
            return Math.max(9.0, Math.min(28.0, 7.0 + shipLength / 14.0));
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
        private final Path aisDataDir;
        private final int aisFileLimit;

        private Options(List<Path> inputFiles, int width, int height, Path aisDataDir, int aisFileLimit) {
            this.inputFiles = inputFiles;
            this.width = width;
            this.height = height;
            this.aisDataDir = aisDataDir;
            this.aisFileLimit = aisFileLimit;
        }

        static Options parse(String[] args) {
            List<Path> inputFiles = new ArrayList<>();
            int width = 1200;
            int height = 900;
            Path aisDataDir = DEFAULT_AIS_DATA_DIR;
            int aisFileLimit = DEFAULT_AIS_FILE_LIMIT;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--width" -> width = Integer.parseInt(requireValue(args, ++i, "--width"));
                    case "--height" -> height = Integer.parseInt(requireValue(args, ++i, "--height"));
                    case "--ais-data" -> aisDataDir = Path.of(requireValue(args, ++i, "--ais-data"));
                    case "--ais-files" -> aisFileLimit = Integer.parseInt(requireValue(args, ++i, "--ais-files"));
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
            return new Options(List.copyOf(inputFiles), width, height, aisDataDir, aisFileLimit);
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
                      --width <px>         Window width. Default: 1200.
                      --height <px>        Window height. Default: 900.
                      --ais-data <dir>     AIS data directory. Default: C:/Users/Owner/AISData.
                      --ais-files <count>  AIS files to load. Default: 1. Use 0 for all files.

                    If no senc files are given, JP34NC8Q, JP34NC8S, and JP34NVPQ
                    are loaded from C:/Users/Owner/senc.
                    """);
        }
    }
}

