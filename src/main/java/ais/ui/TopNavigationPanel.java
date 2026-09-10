package ais.ui;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class TopNavigationPanel extends JPanel {

    public enum Screen {
        HISTORY("過去ログ"),
        LIVE("リアルタイム"),
        AGGREGATE("集計"),
        RECEIVER("受信局設定"),
        SETTINGS("共通設定");

        private final String label;

        Screen(String label) {
            this.label = label;
        }
    }

    private final Map<Screen, JToggleButton> buttons =
            new EnumMap<>(Screen.class);

    public TopNavigationPanel(String receiverName,
                              Consumer<Screen> selection) {
        super(new BorderLayout());
        Objects.requireNonNull(selection, "selection");
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 5));
        ButtonGroup group = new ButtonGroup();
        for (Screen screen : Screen.values()) {
            JToggleButton button = new JToggleButton(screen.label);
            buttons.put(screen, button);
            group.add(button);
            tabs.add(button);
            button.addActionListener(event -> selection.accept(screen));
        }
        buttons.get(Screen.RECEIVER).setEnabled(false);
        buttons.get(Screen.SETTINGS).setEnabled(false);
        buttons.get(Screen.RECEIVER).setToolTipText("後続反復で接続します");
        buttons.get(Screen.SETTINGS).setToolTipText("後続反復で接続します");
        setActive(Screen.HISTORY);
        add(tabs, BorderLayout.WEST);
        add(new JLabel("受信局: " + receiverName + "  "),
                BorderLayout.EAST);
        setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, new Color(160, 165, 170)));
    }

    public void setActive(Screen screen) {
        JToggleButton active = buttons.get(screen);
        if (active != null) {
            active.setSelected(true);
        }
    }

    public void setSwitchingEnabled(boolean enabled) {
        buttons.get(Screen.HISTORY).setEnabled(enabled);
        buttons.get(Screen.LIVE).setEnabled(enabled);
        buttons.get(Screen.AGGREGATE).setEnabled(enabled);
        String tip = enabled ? null : "受信を停止してから切り替えてください";
        buttons.get(Screen.HISTORY).setToolTipText(tip);
        buttons.get(Screen.LIVE).setToolTipText(tip);
        buttons.get(Screen.AGGREGATE).setToolTipText(tip);
    }
}
