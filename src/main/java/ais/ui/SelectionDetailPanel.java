package ais.ui;

import ais.aggregate.InsufficientDataReason;
import ais.aggregate.MetricCounts;
import ais.domain.FreshnessState;
import ais.domain.VesselClass;
import ais.ui.viewmodel.GridDetailViewModel;
import ais.ui.viewmodel.VesselDetailViewModel;
import ais.ui.viewmodel.SimulationVesselDetailViewModel;
import ais.simulation.communication.ReceptionOutcome;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.TitledBorder;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Collectors;

public final class SelectionDetailPanel extends JPanel {

    private final JPanel vessel = new JPanel();
    private final JPanel grid = new JPanel();

    public SelectionDetailPanel() {
        setLayout(new GridLayout(2, 1, 0, 6));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        configureContents(vessel);
        configureContents(grid);
        add(createSection(vessel, "選択船舶"));
        add(createSection(grid, "選択格子"));
        setPreferredSize(new Dimension(400, 520));
        showPlaceholder(vessel, "地図上の船舶を選択してください");
        showPlaceholder(grid, "地図上の色付き格子を選択してください");
    }

    public void update(VesselDetailViewModel vesselDetail,
                       GridDetailViewModel gridDetail) {
        update(vesselDetail, gridDetail, null);
    }

    public void update(
            VesselDetailViewModel vesselDetail,
            GridDetailViewModel gridDetail,
            SimulationVesselDetailViewModel simulationDetail) {
        if (vesselDetail == null && simulationDetail == null) {
            showPlaceholder(vessel, "地図上の船舶を選択してください");
        } else {
            populateVessel(vesselDetail, simulationDetail);
        }
        if (gridDetail == null) {
            showPlaceholder(grid, "地図上の色付き格子を選択してください");
        } else {
            populateGrid(gridDetail);
        }
        revalidate();
        repaint();
    }

    private void populateVessel(
            VesselDetailViewModel detail,
            SimulationVesselDetailViewModel simulation) {
        vessel.removeAll();
        int mmsi = detail != null ? detail.mmsi() : simulation.mmsi();
        VesselClass vesselClass = detail != null
                ? detail.vesselClass() : simulation.vesselClass();
        addLine(vessel, "MMSI", String.format("%09d", mmsi));
        if (detail != null) {
            addLine(vessel, "船名", detail.vesselName() == null
                    ? "未取得" : detail.vesselName());
            addLine(vessel, "全長", detail.shipLengthMeters() == null
                    ? "未取得" : detail.shipLengthMeters() + " m");
        }
        addLine(vessel, "Class", classLabel(vesselClass));
        if (detail != null) {
            addLine(vessel, "受信位置", String.format("%.6f, %.6f",
                    detail.latitude(), detail.longitude()));
            addLine(vessel, "船速", decimal(detail.sogKnots(), "kt"));
            addLine(vessel, "対地針路", decimal(detail.cogDegrees(), "°"));
            addLine(vessel, "船首方位", detail.trueHeadingDegrees() == null
                    ? "—" : String.format("%.0f°",
                    detail.trueHeadingDegrees()));
            addLine(vessel, "航行状態", navigationStatus(
                    detail.navigationStatus()));
            addLine(vessel, "メッセージ", "Type " + detail.messageType());
            addLine(vessel, "情報鮮度", freshnessLabel(detail.freshness()));
            addLine(vessel, "経過時間", age(detail.ageSeconds()));
            addLine(vessel, "受信局距離", String.format("%.1f km",
                    detail.receiverDistanceKilometers()));
        }
        if (simulation != null) {
            addLine(vessel, "真位置", String.format("%.6f, %.6f",
                    simulation.truthPosition().latitude(),
                    simulation.truthPosition().longitude()));
            addLine(vessel, "位置差",
                    simulation.positionDifferenceMeters() == null
                            ? "未受信"
                            : String.format("%.0f m",
                            simulation.positionDifferenceMeters()));
            addLine(vessel, "最終受信から",
                    simulation.secondsSinceLastReception() == null
                            ? "未受信"
                            : age(simulation.secondsSinceLastReception()));
            addLine(vessel, "直前判定",
                    outcomeLabel(simulation.lastOutcome()));
            addLine(vessel, "受信確率",
                    simulation.receptionProbability() == null
                            ? "適用外"
                            : String.format("%.1f%%",
                            simulation.receptionProbability() * 100.0));
            addLine(vessel, "距離帯",
                    simulation.distanceBandLabel() == null
                            ? "—" : simulation.distanceBandLabel());
            addLine(vessel, "通信モデル", simulation.modelLabel());
        }
        addVerticalFiller(vessel);
    }

    private static String outcomeLabel(ReceptionOutcome outcome) {
        if (outcome == null) {
            return "—";
        }
        return switch (outcome) {
            case RECEIVED -> "受信";
            case LOST -> "欠落";
            case OUT_OF_MODEL -> "適用外";
        };
    }

    private void populateGrid(GridDetailViewModel detail) {
        grid.removeAll();
        MetricCounts counts = detail.evaluation().counts();
        addLine(grid, "格子ID", detail.cell().toString());
        addLine(grid, "指標値", detail.displayedRatePercent() == null
                ? "—" : String.format("%.1f%%",
                detail.displayedRatePercent()));
        addLine(grid, "受信数", Long.toString(counts.observedCount()));
        addLine(grid, "推定欠落", Long.toString(counts.missingCount()));
        addLine(grid, "期待送信", Long.toString(counts.expectedCount()));
        addLine(grid, "観測時間", duration(counts.observedSeconds()));
        addLine(grid, "鮮度違反時間", duration(counts.staleSeconds()));
        addLine(grid, "対象船舶", Integer.toString(
                detail.evaluation().distinctVesselCount()));
        String judgement = detail.evaluation().hasSufficientData()
                ? "評価対象" : "データ不足: "
                + detail.evaluation().insufficientReasons().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(SelectionDetailPanel::reasonLabel)
                .collect(Collectors.joining("、"));
        addLine(grid, "データ判定", judgement);
        addLine(grid, "受信局距離", String.format("%.1f km",
                detail.receiverDistanceKilometers()));
        addVerticalFiller(grid);
    }

    private static void configureContents(JPanel panel) {
        panel.setLayout(new GridBagLayout());
    }

    private static JScrollPane createSection(JPanel contents, String title) {
        JScrollPane section = new JScrollPane(contents,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(larger(contents.getFont(), Font.BOLD));
        section.setBorder(border);
        section.getVerticalScrollBar().setUnitIncrement(16);
        return section;
    }

    private static void showPlaceholder(JPanel panel, String text) {
        panel.removeAll();
        JLabel message = new JLabel(text, JLabel.CENTER);
        message.setFont(larger(message.getFont(), Font.PLAIN));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(8, 8, 8, 8);
        panel.add(message, constraints);
    }

    private static void addLine(JPanel panel, String label, String value) {
        int row = panel.getComponentCount() / 2;
        JLabel name = new JLabel(label);
        name.setFont(larger(name.getFont(), Font.BOLD));
        GridBagConstraints nameConstraints = new GridBagConstraints();
        nameConstraints.gridx = 0;
        nameConstraints.gridy = row;
        nameConstraints.anchor = GridBagConstraints.NORTHWEST;
        nameConstraints.insets = new Insets(3, 7, 3, 12);
        panel.add(name, nameConstraints);

        JLabel contents = new JLabel(value);
        contents.setFont(larger(contents.getFont(), Font.PLAIN));
        contents.setToolTipText(value);
        GridBagConstraints valueConstraints = new GridBagConstraints();
        valueConstraints.gridx = 1;
        valueConstraints.gridy = row;
        valueConstraints.weightx = 1.0;
        valueConstraints.fill = GridBagConstraints.HORIZONTAL;
        valueConstraints.anchor = GridBagConstraints.NORTHWEST;
        valueConstraints.insets = new Insets(3, 0, 3, 7);
        panel.add(contents, valueConstraints);
    }

    private static void addVerticalFiller(JPanel panel) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = panel.getComponentCount() / 2;
        constraints.gridwidth = 2;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        constraints.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), constraints);
    }

    private static Font larger(Font font, int style) {
        return font.deriveFont(style, font.getSize2D() + 2.0f);
    }

    private static String decimal(Double value, String unit) {
        return value == null ? "—" : String.format("%.1f %s", value, unit);
    }

    private static String classLabel(VesselClass vesselClass) {
        return switch (vesselClass) {
            case CLASS_A -> "Class A";
            case CLASS_B -> "Class B";
            case UNKNOWN -> "不明";
        };
    }

    private static String freshnessLabel(FreshnessState state) {
        return switch (state) {
            case NORMAL -> "正常";
            case CAUTION -> "注意";
            case VIOLATION -> "違反";
            case UNKNOWN -> "不明";
        };
    }

    private static String navigationStatus(Integer code) {
        if (code == null) {
            return "—";
        }
        String label = switch (code) {
            case 0 -> "機走中";
            case 1 -> "錨泊中";
            case 2 -> "操縦不能";
            case 3 -> "操縦性能制限中";
            case 5 -> "係留中";
            case 7 -> "漁ろう中";
            case 8 -> "帆走中";
            default -> "状態";
        };
        return label + " (" + code + ")";
    }

    private static String age(long seconds) {
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return minutes == 0 ? remainder + "秒"
                : minutes + "分" + remainder + "秒";
    }

    private static String duration(double seconds) {
        Duration duration = Duration.ofMillis(Math.round(seconds * 1_000));
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        long remainder = duration.minusHours(hours)
                .minusMinutes(minutes).toSeconds();
        return String.format("%02d:%02d:%02d", hours, minutes, remainder);
    }

    private static String reasonLabel(InsufficientDataReason reason) {
        return switch (reason) {
            case EXPECTED_COUNT_BELOW_MINIMUM -> "期待送信数30件未満";
            case DISTINCT_VESSELS_BELOW_MINIMUM -> "異なる船舶3隻未満";
        };
    }
}
