package ais.ui;

import ais.domain.VesselClass;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.time.Duration;
import java.util.Set;

public final class DisplayOptionsPanel extends JPanel {

    private final JComboBox<ClassChoice> vesselClass =
            new JComboBox<>(ClassChoice.values());
    private final JComboBox<TrailChoice> trail =
            new JComboBox<>(TrailChoice.values());
    private final JCheckBox heatmap = new JCheckBox("2km格子", true);
    private final JCheckBox tenKilometers = new JCheckBox("10km圏", true);
    private final JCheckBox thirtyKilometers = new JCheckBox("30km圏", true);
    private final JCheckBox receiver = new JCheckBox("受信局", true);
    private final JCheckBox vessels = new JCheckBox("船舶記号", true);

    public DisplayOptionsPanel(Listener listener) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        TitledBorder border = BorderFactory.createTitledBorder("表示条件");
        border.setTitleFont(larger(border.getTitleFont(), Font.BOLD));
        setBorder(border);
        JPanel filterRow = row();
        JLabel classLabel = new JLabel("Class:");
        enlarge(classLabel);
        enlarge(vesselClass);
        filterRow.add(classLabel);
        filterRow.add(vesselClass);
        add(filterRow);
        enlarge(heatmap);
        enlarge(tenKilometers);
        enlarge(thirtyKilometers);
        JPanel layers1 = row();
        layers1.add(heatmap);
        layers1.add(tenKilometers);
        layers1.add(thirtyKilometers);
        add(layers1);
        enlarge(receiver);
        enlarge(vessels);
        JPanel layers2 = row();
        layers2.add(receiver);
        layers2.add(vessels);
        add(layers2);
        JPanel trailRow = row();
        JLabel trailLabel = new JLabel("選択航跡:");
        enlarge(trailLabel);
        enlarge(trail);
        trailRow.add(trailLabel);
        trail.setSelectedItem(TrailChoice.MINUTES_60);
        trailRow.add(trail);
        add(trailRow);

        vesselClass.addActionListener(event -> listener.onFilterChanged(
                selectedClasses(), selectedTrail()));
        trail.addActionListener(event -> listener.onFilterChanged(
                selectedClasses(), selectedTrail()));
        heatmap.addActionListener(event -> notifyLayers(listener));
        tenKilometers.addActionListener(event -> notifyLayers(listener));
        thirtyKilometers.addActionListener(event -> notifyLayers(listener));
        receiver.addActionListener(event -> notifyLayers(listener));
        vessels.addActionListener(event -> notifyLayers(listener));
        setMaximumSize(new Dimension(
                Integer.MAX_VALUE, getPreferredSize().height));
    }

    public Set<VesselClass> selectedClasses() {
        ClassChoice selected = (ClassChoice) vesselClass.getSelectedItem();
        return selected == null ? ClassChoice.ALL.classes : selected.classes;
    }

    public Duration selectedTrail() {
        TrailChoice selected = (TrailChoice) trail.getSelectedItem();
        return selected == null
                ? Duration.ofMinutes(60) : selected.duration;
    }

    private void notifyLayers(Listener listener) {
        listener.onLayersChanged(
                heatmap.isSelected(), tenKilometers.isSelected(),
                thirtyKilometers.isSelected(), receiver.isSelected(),
                vessels.isSelected());
    }

    private static JPanel row() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3)) {
            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private static void enlarge(Component component) {
        component.setFont(larger(component.getFont(), Font.PLAIN));
    }

    private static Font larger(Font font, int style) {
        Font base = font == null ? new JLabel().getFont() : font;
        return base.deriveFont(style, base.getSize2D() + 2.0f);
    }

    public interface Listener {
        void onFilterChanged(Set<VesselClass> vesselClasses,
                             Duration trailDuration);

        void onLayersChanged(boolean heatmap, boolean tenKilometers,
                             boolean thirtyKilometers, boolean receiver,
                             boolean vessels);
    }

    private enum ClassChoice {
        ALL("全船舶", Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B)),
        A("Class A", Set.of(VesselClass.CLASS_A)),
        B("Class B", Set.of(VesselClass.CLASS_B));

        private final String label;
        private final Set<VesselClass> classes;

        ClassChoice(String label, Set<VesselClass> classes) {
            this.label = label;
            this.classes = classes;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum TrailChoice {
        MINUTES_10("10分", Duration.ofMinutes(10)),
        MINUTES_30("30分", Duration.ofMinutes(30)),
        MINUTES_60("60分", Duration.ofMinutes(60));

        private final String label;
        private final Duration duration;

        TrailChoice(String label, Duration duration) {
            this.label = label;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
