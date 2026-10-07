package ais.ui;

import ais.domain.GeoPosition;
import ais.domain.TrailPoint;
import ais.map.MapDataset;
import ais.map.MapFeature;
import ais.map.MapFeatureType;
import ais.map.MapHitTester;
import ais.map.MapViewport;
import ais.map.MercatorMapProjection;
import ais.map.ScreenPoint;
import ais.spatial.GridCellId;
import ais.spatial.GridDefinition;
import ais.spatial.ProjectedPoint;
import ais.spatial.Utm53NProjector;
import ais.ui.viewmodel.GridMapItem;
import ais.ui.viewmodel.MapViewModel;
import ais.ui.viewmodel.SimulationOverlayViewModel;
import ais.ui.viewmodel.SimulationTruthMapItem;
import ais.ui.viewmodel.VesselMapItem;
import ais.simulation.communication.ReceptionOutcome;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class MapCanvas extends JPanel {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z");

    public static final Color SEA = new Color(218, 235, 242);
    private static final Color LAND = new Color(226, 224, 211);
    private static final Color LAND_LINE = new Color(88, 101, 88);
    private static final Color COAST = new Color(35, 64, 74);
    private static final Color RIVER = new Color(92, 151, 178, 190);
    private static final Color INSUFFICIENT_FILL =
            new Color(115, 120, 125, 105);
    private static final Color INSUFFICIENT_HATCH =
            new Color(65, 65, 65, 110);
    private static final double MARGIN = 24.0;
    private static final double ZOOM_STEP = 1.18;

    private final MapDataset dataset;
    private final MapViewport viewport;
    private final HeatmapColorScale heatmapColors = new HeatmapColorScale();
    private final VesselSymbolStyle vesselStyle = new VesselSymbolStyle();
    private final MapHitTester hitTester = new MapHitTester();
    private final Utm53NProjector gridProjector = new Utm53NProjector();

    private MapViewModel viewModel;
    private SimulationOverlayViewModel simulationOverlay;
    private Consumer<Integer> vesselSelection = ignored -> { };
    private Consumer<GridCellId> gridSelection = ignored -> { };
    private Runnable clearSelection = () -> { };
    private boolean showHeatmap = true;
    private boolean showTenKilometers = true;
    private boolean showThirtyKilometers = true;
    private boolean showReceiver = true;
    private boolean showVessels = true;
    private boolean legendExpanded = true;
    private boolean exportRendering;
    private Rectangle legendToggleBounds = new Rectangle();
    private BufferedImage terrainCache;
    private long terrainRevision = -1;
    private Point lastDragPoint;
    private Point pressedPoint;

    public MapCanvas(MapDataset dataset) {
        this.dataset = dataset;
        viewport = new MapViewport(dataset,
                new MercatorMapProjection());
        setBackground(SEA);
        setFocusable(true);
        addMouseWheelListener(this::handleMouseWheel);
        MouseHandler mouse = new MouseHandler();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "clear-map-selection");
        getActionMap().put("clear-map-selection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                clearSelection.run();
            }
        });
    }

    public void setViewModel(MapViewModel viewModel) {
        this.viewModel = viewModel;
        repaint();
    }

    public void setSimulationOverlay(
            SimulationOverlayViewModel simulationOverlay) {
        this.simulationOverlay = simulationOverlay;
        repaint();
    }

    public void setSelectionListeners(
            Consumer<Integer> vesselSelection,
            Consumer<GridCellId> gridSelection,
            Runnable clearSelection) {
        this.vesselSelection = vesselSelection;
        this.gridSelection = gridSelection;
        this.clearSelection = clearSelection;
    }

    public void zoomIn() {
        viewport.zoomAt(ZOOM_STEP, getWidth() / 2.0,
                getHeight() / 2.0, getWidth(), getHeight());
        repaint();
    }

    public void zoomOut() {
        viewport.zoomAt(1.0 / ZOOM_STEP, getWidth() / 2.0,
                getHeight() / 2.0, getWidth(), getHeight());
        repaint();
    }

    public void resetView() {
        viewport.reset();
        repaint();
    }

    public double zoom() {
        return viewport.zoom();
    }

    public BufferedImage renderForExport() {
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        boolean previousLegend = legendExpanded;
        exportRendering = true;
        legendExpanded = true;
        Graphics2D graphics = image.createGraphics();
        try {
            printAll(graphics);
        } finally {
            graphics.dispose();
            legendExpanded = previousLegend;
            exportRendering = false;
            repaint();
        }
        return image;
    }

    public void setLayers(boolean heatmap, boolean tenKilometers,
                          boolean thirtyKilometers, boolean receiver,
                          boolean vessels) {
        showHeatmap = heatmap;
        showTenKilometers = tenKilometers;
        showThirtyKilometers = thirtyKilometers;
        showReceiver = receiver;
        showVessels = vessels;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g.setColor(SEA);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (showHeatmap && viewModel != null) {
                drawHeatmap(g);
            }
            drawTerrainCached(g);
            if (viewModel != null) {
                drawDistanceCircles(g);
                if (showReceiver) {
                    drawReceiver(g);
                }
                drawSelectedTrail(g);
                if (showVessels) {
                    drawVessels(g);
                }
                if (simulationOverlay != null) {
                    drawSimulationOverlay(g);
                }
        drawLegend(g);
        if (exportRendering) {
            drawExportMetadata(g);
        }
            } else {
                drawEmptyMessage(g);
            }
            g.setColor(new Color(42, 52, 56, 120));
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        } finally {
            g.dispose();
        }
    }

    private void drawTerrainCached(Graphics2D g) {
        if (terrainCache == null
                || terrainCache.getWidth() != getWidth()
                || terrainCache.getHeight() != getHeight()
                || terrainRevision != viewport.revision()) {
            terrainCache = new BufferedImage(
                    Math.max(1, getWidth()), Math.max(1, getHeight()),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D cacheGraphics = terrainCache.createGraphics();
            try {
                cacheGraphics.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                renderTerrain(cacheGraphics);
            } finally {
                cacheGraphics.dispose();
            }
            terrainRevision = viewport.revision();
        }
        g.drawImage(terrainCache, 0, 0, null);
    }

    private void renderTerrain(Graphics2D g) {
        for (MapFeature feature : dataset.features()) {
            if (feature.type() == MapFeatureType.LAND) {
                Path2D path = featurePath(feature);
                g.setColor(LAND);
                g.fill(path);
                g.setColor(LAND_LINE);
                g.setStroke(new BasicStroke(0.7f));
                g.draw(path);
            }
        }
        for (MapFeature feature : dataset.features()) {
            if (feature.type() == MapFeatureType.RIVER) {
                g.setColor(RIVER);
                g.setStroke(new BasicStroke(0.9f));
                g.draw(featurePath(feature));
            } else if (feature.type() == MapFeatureType.COASTLINE) {
                g.setColor(COAST);
                g.setStroke(new BasicStroke(
                        feature.sourceKind().equals("COALNE")
                                ? 1.4f : 0.9f));
                g.draw(featurePath(feature));
            }
        }
    }

    private void drawHeatmap(Graphics2D g) {
        AnalysisGridGeometry geometry = gridGeometry();
        for (GridMapItem item : viewModel.gridCells()) {
            Path2D cell = geometry.path(item.cell());
            if (!item.evaluation().hasSufficientData()
                    || item.displayedRatePercent() == null) {
                paintInsufficientData(g, cell);
            } else {
                g.setColor(heatmapColors.colorFor(
                        item.displayedRatePercent()));
                g.fill(cell);
            }
            g.setColor(new Color(60, 70, 75, 80));
            g.setStroke(new BasicStroke(0.45f));
            g.draw(cell);
            if (item.selected()) {
                g.setColor(new Color(25, 25, 25, 220));
                g.setStroke(new BasicStroke(3.0f));
                g.draw(cell);
            }
        }
    }

    static void paintInsufficientData(Graphics2D g, Shape shape) {
        g.setColor(INSUFFICIENT_FILL);
        g.fill(shape);
        drawHatching(g, shape);
    }

    private static void drawHatching(Graphics2D g, Shape shape) {
        Shape oldClip = g.getClip();
        g.clip(shape);
        g.setColor(INSUFFICIENT_HATCH);
        g.setStroke(new BasicStroke(0.7f));
        Rectangle bounds = shape.getBounds();
        for (int x = bounds.x - bounds.height;
                x < bounds.x + bounds.width; x += 8) {
            g.drawLine(x, bounds.y + bounds.height,
                    x + bounds.height, bounds.y);
        }
        g.setClip(oldClip);
    }

    private void drawDistanceCircles(Graphics2D g) {
        GeoPosition receiver = viewModel.receiver().position();
        if (showTenKilometers) {
            drawDistanceCircle(g, receiver, 10.0,
                    new Color(34, 82, 105, 150), 1.0f, null);
        }
        if (showThirtyKilometers) {
            drawDistanceCircle(g, receiver, 30.0,
                    new Color(20, 64, 96, 210), 2.0f, "30 km");
        }
    }

    private void drawDistanceCircle(Graphics2D g, GeoPosition center,
                                    double kilometers, Color color,
                                    float stroke, String label) {
        Path2D circle = new Path2D.Double();
        ScreenPoint labelPoint = null;
        for (int bearing = 0; bearing <= 360; bearing += 4) {
            GeoPosition point = destination(center, kilometers, bearing);
            ScreenPoint screen = screen(point);
            if (bearing == 0) {
                circle.moveTo(screen.x(), screen.y());
                labelPoint = screen;
            } else {
                circle.lineTo(screen.x(), screen.y());
            }
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(stroke));
        g.draw(circle);
        if (label != null && labelPoint != null) {
            g.drawString(label, (float) labelPoint.x() + 4,
                    (float) labelPoint.y() - 3);
        }
    }

    private void drawReceiver(Graphics2D g) {
        ScreenPoint point = screen(viewModel.receiver().position());
        int x = (int) Math.round(point.x());
        int y = (int) Math.round(point.y());
        g.setColor(new Color(20, 38, 55));
        g.setStroke(new BasicStroke(2.0f));
        g.drawLine(x, y + 8, x, y - 9);
        g.drawLine(x - 5, y + 8, x + 5, y + 8);
        g.drawArc(x - 9, y - 10, 18, 13, 30, 120);
        g.drawString(viewModel.receiver().name(), x + 10, y - 8);
    }

    private void drawSelectedTrail(Graphics2D g) {
        for (VesselMapItem vessel : viewModel.vessels()) {
            if (!vessel.selected() || vessel.trail().size() < 2) {
                continue;
            }
            Path2D trail = new Path2D.Double();
            boolean first = true;
            for (TrailPoint point : vessel.trail()) {
                ScreenPoint screen = screen(point.position());
                if (first) {
                    trail.moveTo(screen.x(), screen.y());
                    first = false;
                } else {
                    trail.lineTo(screen.x(), screen.y());
                }
            }
            g.setColor(new Color(255, 255, 255, 225));
            g.setStroke(new BasicStroke(3.4f));
            g.draw(trail);
            g.setColor(new Color(48, 65, 76, 230));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(trail);
        }
    }

    private void drawVessels(Graphics2D g) {
        for (VesselMapItem vessel : viewModel.vessels()) {
            ScreenPoint point = screen(vessel.position());
            Path2D symbol = vesselShape(point,
                    vessel.directionDegrees() == null
                            ? 0.0 : vessel.directionDegrees());
            if (vessel.selected()) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(5.0f));
                g.draw(symbol);
            }
            g.setColor(vesselStyle.fill(vessel.freshness()));
            g.fill(symbol);
            g.setColor(vesselStyle.classOutline(vessel.vesselClass()));
            g.setStroke(new BasicStroke(2.0f));
            g.draw(symbol);
        }
    }

    private void drawSimulationOverlay(Graphics2D g) {
        for (SimulationTruthMapItem vessel
                : simulationOverlay.truthVessels()) {
            if (vessel.selected()
                    && vessel.lastReceivedPosition() != null) {
                ScreenPoint truth = screen(vessel.truthPosition());
                ScreenPoint received = screen(vessel.lastReceivedPosition());
                g.setColor(new Color(25, 125, 155, 190));
                g.setStroke(new BasicStroke(
                        1.8f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND,
                        10.0f,
                        new float[]{7.0f, 5.0f},
                        0.0f));
                g.drawLine(
                        (int) Math.round(received.x()),
                        (int) Math.round(received.y()),
                        (int) Math.round(truth.x()),
                        (int) Math.round(truth.y()));
            }
            if (vessel.selected() && vessel.truthTrail().size() >= 2) {
                Path2D trail = new Path2D.Double();
                boolean first = true;
                for (TrailPoint point : vessel.truthTrail()) {
                    ScreenPoint screen = screen(point.position());
                    if (first) {
                        trail.moveTo(screen.x(), screen.y());
                        first = false;
                    } else {
                        trail.lineTo(screen.x(), screen.y());
                    }
                }
                g.setColor(new Color(0, 130, 165, 210));
                g.setStroke(new BasicStroke(
                        2.0f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND,
                        10.0f,
                        new float[]{4.0f, 4.0f},
                        0.0f));
                g.draw(trail);
            }

            ScreenPoint point = screen(vessel.truthPosition());
            Path2D symbol = vesselShape(
                    point,
                    vessel.directionDegrees() == null
                            ? 0.0 : vessel.directionDegrees());
            if (vessel.selected()) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(5.0f));
                g.draw(symbol);
            }
            g.setColor(new Color(240, 255, 255, 155));
            g.fill(symbol);
            g.setColor(truthColor(vessel.lastOutcome()));
            g.setStroke(new BasicStroke(
                    2.2f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    10.0f,
                    new float[]{5.0f, 3.0f},
                    0.0f));
            g.draw(symbol);
        }
    }

    private static Color truthColor(ReceptionOutcome outcome) {
        if (outcome == null || outcome == ReceptionOutcome.RECEIVED) {
            return new Color(0, 120, 160);
        }
        return outcome == ReceptionOutcome.LOST
                ? new Color(220, 105, 20)
                : new Color(95, 100, 105);
    }

    private void drawLegend(Graphics2D g) {
        int width = 242;
        int height = legendExpanded
                ? simulationOverlay == null ? 226 : 252
                : 32;
        int x = 12;
        int y = Math.max(12, getHeight() - height - 12);
        legendToggleBounds = new Rectangle(x, y, width, 30);
        g.setColor(new Color(250, 252, 253, 225));
        g.fillRoundRect(x, y, width, height, 12, 12);
        g.setColor(new Color(45, 55, 60, 190));
        g.drawRoundRect(x, y, width, height, 12, 12);
        g.drawString(viewModel.metric().toString(), x + 10, y + 18);
        g.drawString(legendExpanded ? "－" : "＋", x + width - 22,
                y + 18);
        if (!legendExpanded) {
            return;
        }
        String[] labels = {"0–1", "1–5", "5–10", "10–20", "20–30",
                "30–50", "50–70", "70–90", "90–100"};
        for (int index = 0; index < labels.length; index++) {
            int column = index / 5;
            int row = index % 5;
            int itemX = x + 10 + column * 108;
            int itemY = y + 31 + row * 19;
            g.setColor(heatmapColors.colors().get(index));
            g.fillRect(itemX, itemY, 17, 12);
            g.setColor(Color.DARK_GRAY);
            g.drawString(labels[index] + "%", itemX + 22, itemY + 11);
        }
        int freshnessY = y + 137;
        g.drawString("船舶鮮度", x + 10, freshnessY);
        drawLegendDot(g, x + 18, freshnessY + 17,
                vesselStyle.fill(ais.domain.FreshnessState.NORMAL), "正常");
        drawLegendDot(g, x + 78, freshnessY + 17,
                vesselStyle.fill(ais.domain.FreshnessState.CAUTION), "注意");
        drawLegendDot(g, x + 138, freshnessY + 17,
                vesselStyle.fill(ais.domain.FreshnessState.VIOLATION), "違反");
        drawLegendDot(g, x + 198, freshnessY + 17,
                vesselStyle.fill(ais.domain.FreshnessState.UNKNOWN), "不明");
        g.setColor(vesselStyle.classOutline(
                ais.domain.VesselClass.CLASS_A));
        g.drawString("□ Class A", x + 10, y + 187);
        g.setColor(vesselStyle.classOutline(
                ais.domain.VesselClass.CLASS_B));
        g.drawString("□ Class B", x + 92, y + 187);
        Rectangle insufficientSwatch = new Rectangle(
                x + 10, y + 196, 17, 12);
        paintInsufficientData(g, insufficientSwatch);
        g.setColor(Color.DARK_GRAY);
        g.drawRect(insufficientSwatch.x, insufficientSwatch.y,
                insufficientSwatch.width, insufficientSwatch.height);
        g.drawString("データ不足", x + 32, y + 207);
        g.setColor(Color.DARK_GRAY);
        g.drawString(String.format("鮮度基準: %.0f倍",
                viewModel.freshnessMultiplier()), x + 126, y + 207);
        if (simulationOverlay != null) {
            int simulationY = y + 231;
            g.setColor(new Color(40, 60, 70));
            g.drawString("▲ 受信位置", x + 10, simulationY);
            g.setColor(new Color(0, 120, 160));
            g.drawString("△ 真位置（破線）", x + 103, simulationY);
        }
    }

    private static void drawLegendDot(Graphics2D g, int x, int y,
                                      Color color, String label) {
        g.setColor(color);
        g.fillOval(x - 5, y - 8, 10, 10);
        g.setColor(Color.DARK_GRAY);
        g.drawString(label, x + 7, y);
    }

    private void drawEmptyMessage(Graphics2D g) {
        String message = "一日分のAISログを選択してください";
        int width = g.getFontMetrics().stringWidth(message);
        g.setColor(new Color(30, 45, 55, 190));
        g.drawString(message, Math.max(12, (getWidth() - width) / 2),
                Math.max(30, getHeight() / 2));
    }

    private void drawExportMetadata(Graphics2D g) {
        String line = EXPORT_TIME.format(
                viewModel.displayTime().atZone(JAPAN))
                + " / " + viewModel.metric()
                + " / " + viewModel.vesselClassLabel()
                + " / 受信局: " + viewModel.receiver().name()
                + " / 解析条件: " + viewModel.analysisRulesVersion();
        int width = Math.min(getWidth() - 24,
                g.getFontMetrics().stringWidth(line) + 18);
        int x = Math.max(12, getWidth() - width - 12);
        int y = 12;
        g.setColor(new Color(250, 252, 253, 225));
        g.fillRoundRect(x, y, width, 28, 8, 8);
        g.setColor(new Color(35, 45, 52));
        g.drawRoundRect(x, y, width, 28, 8, 8);
        g.drawString(line, x + 8, y + 19);
        if (viewModel.selectedMmsi() != null) {
            g.drawString("選択船舶 MMSI " + viewModel.selectedMmsi(),
                    x + 8, y + 43);
        }
    }

    private Path2D featurePath(MapFeature feature) {
        Path2D path = new Path2D.Double();
        boolean first = true;
        for (GeoPosition position : feature.points()) {
            ScreenPoint point = screen(position);
            if (first) {
                path.moveTo(point.x(), point.y());
                first = false;
            } else {
                path.lineTo(point.x(), point.y());
            }
        }
        if (feature.area()) {
            path.closePath();
        }
        return path;
    }

    private Path2D vesselShape(ScreenPoint center, double headingDegrees) {
        double angle = Math.toRadians(headingDegrees - 90.0);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double sideX = -sin;
        double sideY = cos;
        double length = 11.0;
        double width = 7.0;
        double tipX = center.x() + cos * length;
        double tipY = center.y() + sin * length;
        double sternX = center.x() - cos * length * 0.65;
        double sternY = center.y() - sin * length * 0.65;
        Path2D shape = new Path2D.Double();
        shape.moveTo(tipX, tipY);
        shape.lineTo(sternX + sideX * width,
                sternY + sideY * width);
        shape.lineTo(sternX - sideX * width,
                sternY - sideY * width);
        shape.closePath();
        return shape;
    }

    private AnalysisGridGeometry gridGeometry() {
        return new AnalysisGridGeometry(new GridDefinition(
                GridCellId.UTM_ZONE_53_NORTH,
                viewModel.gridOriginEasting(),
                viewModel.gridOriginNorthing(),
                viewModel.gridSizeMeters()));
    }

    private ScreenPoint screen(GeoPosition position) {
        return viewport.toScreen(position, getWidth(), getHeight(), MARGIN);
    }

    private void handleMouseWheel(MouseWheelEvent event) {
        double factor = Math.pow(ZOOM_STEP,
                -event.getPreciseWheelRotation());
        viewport.zoomAt(factor, event.getX(), event.getY(),
                getWidth(), getHeight());
        repaint();
        event.consume();
    }

    private void handleClick(Point point) {
        if (legendToggleBounds.contains(point)) {
            legendExpanded = !legendExpanded;
            repaint();
            return;
        }
        if (viewModel == null) {
            clearSelection.run();
            return;
        }
        Map<Integer, GeoPosition> vesselPositions = new HashMap<>();
        if (showVessels) {
            viewModel.vessels().forEach(vessel -> vesselPositions.put(
                    vessel.mmsi(), vessel.position()));
        }
        if (simulationOverlay != null) {
            simulationOverlay.truthVessels().forEach(vessel ->
                    vesselPositions.put(
                            vessel.mmsi(), vessel.truthPosition()));
        }
        var vessel = hitTester.vesselAt(
                point.x, point.y, vesselPositions, viewport,
                getWidth(), getHeight(), MARGIN, 15.0);
        if (vessel.isPresent()) {
            vesselSelection.accept(vessel.get());
            return;
        }
        if (showHeatmap) {
            Set<GridCellId> cells = new HashSet<>();
            viewModel.gridCells().forEach(item -> cells.add(item.cell()));
            GridDefinition grid = new GridDefinition(
                    GridCellId.UTM_ZONE_53_NORTH,
                    viewModel.gridOriginEasting(),
                    viewModel.gridOriginNorthing(),
                    viewModel.gridSizeMeters());
            var cell = hitTester.gridCellAt(
                    point.x, point.y, cells, viewport,
                    getWidth(), getHeight(), MARGIN,
                    gridProjector, grid);
            if (cell.isPresent()) {
                gridSelection.accept(cell.get());
                return;
            }
        }
        clearSelection.run();
    }

    private static GeoPosition destination(GeoPosition start,
                                           double kilometers,
                                           double bearingDegrees) {
        double angularDistance = kilometers / 6_371.0;
        double bearing = Math.toRadians(bearingDegrees);
        double latitude = Math.toRadians(start.latitude());
        double longitude = Math.toRadians(start.longitude());
        double targetLatitude = Math.asin(
                Math.sin(latitude) * Math.cos(angularDistance)
                        + Math.cos(latitude) * Math.sin(angularDistance)
                        * Math.cos(bearing));
        double targetLongitude = longitude + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance)
                        * Math.cos(latitude),
                Math.cos(angularDistance)
                        - Math.sin(latitude) * Math.sin(targetLatitude));
        return new GeoPosition(Math.toDegrees(targetLatitude),
                Math.toDegrees(targetLongitude));
    }

    private final class AnalysisGridGeometry {
        private final GridDefinition grid;

        private AnalysisGridGeometry(GridDefinition grid) {
            this.grid = grid;
        }

        private Path2D path(GridCellId cell) {
            double west = grid.westernBoundary(cell);
            double south = grid.southernBoundary(cell);
            double size = grid.cellSizeMeters();
            GeoPosition[] corners = {
                    gridProjector.unproject(new ProjectedPoint(west, south)),
                    gridProjector.unproject(new ProjectedPoint(
                            west + size, south)),
                    gridProjector.unproject(new ProjectedPoint(
                            west + size, south + size)),
                    gridProjector.unproject(new ProjectedPoint(
                            west, south + size))};
            Path2D path = new Path2D.Double();
            for (int index = 0; index < corners.length; index++) {
                ScreenPoint point = screen(corners[index]);
                if (index == 0) {
                    path.moveTo(point.x(), point.y());
                } else {
                    path.lineTo(point.x(), point.y());
                }
            }
            path.closePath();
            return path;
        }
    }

    private final class MouseHandler extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event)) {
                pressedPoint = event.getPoint();
                lastDragPoint = event.getPoint();
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                requestFocusInWindow();
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (lastDragPoint == null) {
                return;
            }
            Point point = event.getPoint();
            viewport.panBy(point.x - lastDragPoint.x,
                    point.y - lastDragPoint.y);
            lastDragPoint = point;
            repaint();
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event)
                    && pressedPoint != null
                    && pressedPoint.distance(event.getPoint()) < 4.0) {
                handleClick(event.getPoint());
            }
            pressedPoint = null;
            lastDragPoint = null;
            setCursor(Cursor.getDefaultCursor());
        }
    }
}
