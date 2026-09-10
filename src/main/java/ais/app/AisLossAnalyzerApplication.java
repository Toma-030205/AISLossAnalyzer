package ais.app;

import ais.storage.SqliteDatabase;
import ais.ui.MainFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;

public final class AisLossAnalyzerApplication {

    private AisLossAnalyzerApplication() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            ApplicationContext context = ApplicationContext.initialize(
                    options.sencRoot(), options.aisDataRoot(),
                    options.database());
            if (options.validateOnly()) {
                System.out.printf(
                        "Application data validated: mapFeatures=%,d, historicalDays=%,d, unclassifiedFiles=%,d%n",
                        context.mapDataset().features().size(),
                        context.historicalFilesByDate().size(),
                        context.unclassifiedHistoricalFileCount());
                context.close();
                return;
            }
            SwingUtilities.invokeLater(() -> {
                useSystemLookAndFeel();
                new MainFrame(context).setVisible(true);
            });
        } catch (Exception failure) {
            failure.printStackTrace();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null,
                    failure.getMessage(),
                    "AISLossAnalyzerを起動できません",
                    JOptionPane.ERROR_MESSAGE));
        }
    }

    private static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform appearance remains usable.
        }
    }

    record Options(Path sencRoot, Path aisDataRoot,
                   SqliteDatabase database, boolean validateOnly) {

        static Options parse(String[] args) {
            Path working = Path.of("").toAbsolutePath().normalize();
            Path senc = working.resolve("senc");
            Path ais = working.resolve("ais");
            SqliteDatabase database = SqliteDatabase.defaultDatabase();
            boolean validateOnly = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--senc" -> senc = Path.of(
                            requireValue(args, ++index, "--senc"));
                    case "--ais-data" -> ais = Path.of(
                            requireValue(args, ++index, "--ais-data"));
                    case "--database" -> database = new SqliteDatabase(
                            Path.of(requireValue(
                                    args, ++index, "--database")));
                    case "--validate-only" -> validateOnly = true;
                    default -> throw new IllegalArgumentException(
                            "不明な起動引数: " + args[index]);
                }
            }
            return new Options(senc, ais, database, validateOnly);
        }

        private static String requireValue(
                String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(
                        option + "にはパスが必要です");
            }
            return args[index];
        }
    }
}
