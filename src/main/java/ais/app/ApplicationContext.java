package ais.app;

import ais.analysis.DefaultAnalysisEngine;
import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.input.history.HistoricalFileCatalog;
import ais.map.MapDataset;
import ais.map.SencCatalog;
import ais.map.SencReader;
import ais.storage.AnalysisResultStore;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import ais.input.live.UdpSourceConfig;
import ais.export.ExportService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ApplicationContext implements AutoCloseable {

    public static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final MapDataset mapDataset;
    private final Map<LocalDate, List<Path>> historicalFilesByDate;
    private final int unclassifiedHistoricalFileCount;
    private final ReceiverProfile receiverProfile;
    private final AnalysisProfile analysisProfile;
    private final HistoricalAnalysisService historicalAnalysisService;
    private final LiveAnalysisService liveAnalysisService;
    private final AggregateQueryService aggregateQueryService;
    private final ExportService exportService;

    private ApplicationContext(
            MapDataset mapDataset,
            Map<LocalDate, List<Path>> historicalFilesByDate,
            int unclassifiedHistoricalFileCount,
            ReceiverProfile receiverProfile,
            AnalysisProfile analysisProfile,
            HistoricalAnalysisService historicalAnalysisService,
            LiveAnalysisService liveAnalysisService,
            AggregateQueryService aggregateQueryService,
            ExportService exportService) {
        this.mapDataset = mapDataset;
        this.historicalFilesByDate = Map.copyOf(historicalFilesByDate);
        this.unclassifiedHistoricalFileCount =
                unclassifiedHistoricalFileCount;
        this.receiverProfile = receiverProfile;
        this.analysisProfile = analysisProfile;
        this.historicalAnalysisService = historicalAnalysisService;
        this.liveAnalysisService = liveAnalysisService;
        this.aggregateQueryService = aggregateQueryService;
        this.exportService = exportService;
    }

    public static ApplicationContext initialize(
            Path sencRoot,
            Path aisDataRoot,
            SqliteDatabase database) throws IOException {
        Objects.requireNonNull(sencRoot, "sencRoot");
        Objects.requireNonNull(aisDataRoot, "aisDataRoot");
        Objects.requireNonNull(database, "database");

        List<Path> sencFiles = new SencCatalog().scan(sencRoot);
        MapDataset mapDataset = new SencReader().read(sencFiles);
        HistoricalFileCatalog catalog = HistoricalFileCatalog.scan(
                aisDataRoot, JAPAN);
        Map<LocalDate, List<Path>> filesByDate = new LinkedHashMap<>();
        for (LocalDate date : catalog.availableDates()) {
            filesByDate.put(date, catalog.filesFor(date));
        }

        new SchemaMigrator(database).migrate();
        JdbcReceiverProfileRepository receivers =
                new JdbcReceiverProfileRepository(database);
        ReceiverProfile receiver = receivers.findEffectiveOn(LocalDate.now())
                .orElseGet(() -> {
                    List<ReceiverProfile> existing = receivers.findAll();
                    if (!existing.isEmpty()) {
                        return existing.getLast();
                    }
                    ReceiverProfile migrationDefault =
                            defaultReceiverProfile();
                    receivers.save(migrationDefault);
                    return migrationDefault;
                });
        JdbcAnalysisProfileRepository profiles =
                new JdbcAnalysisProfileRepository(database);
        AnalysisProfile profile = profiles.findById(
                        AnalysisProfile.phaseOneDefaults().id())
                .orElseGet(() -> {
                    AnalysisProfile value =
                            AnalysisProfile.phaseOneDefaults();
                    profiles.save(value);
                    return value;
                });
        HistoricalAnalysisService service = new HistoricalAnalysisService(
                date -> receivers.findEffectiveOn(date).orElseThrow(
                        () -> new IllegalStateException(
                                date + "に有効な受信局プロファイルがありません")),
                profile,
                new HistoricalReplayLoader(JAPAN),
                new AnalysisResultStore(database),
                DefaultAnalysisEngine::new);
        AnalysisResultStore resultStore = new AnalysisResultStore(database);
        LiveAnalysisService liveService = new LiveAnalysisService(
                receiver, profile, UdpSourceConfig.defaults(), resultStore);
        AggregateQueryService aggregateService =
                new AggregateQueryService(database);
        ExportService exportService = new ExportService();
        return new ApplicationContext(
                mapDataset, filesByDate,
                catalog.unclassifiedFiles().size(),
                receiver, profile, service, liveService,
                aggregateService, exportService);
    }

    public MapDataset mapDataset() {
        return mapDataset;
    }

    public Map<LocalDate, List<Path>> historicalFilesByDate() {
        return historicalFilesByDate;
    }

    public int unclassifiedHistoricalFileCount() {
        return unclassifiedHistoricalFileCount;
    }

    public ReceiverProfile receiverProfile() {
        return receiverProfile;
    }

    public AnalysisProfile analysisProfile() {
        return analysisProfile;
    }

    public HistoricalAnalysisService historicalAnalysisService() {
        return historicalAnalysisService;
    }

    public LiveAnalysisService liveAnalysisService() {
        return liveAnalysisService;
    }

    public AggregateQueryService aggregateQueryService() {
        return aggregateQueryService;
    }

    public ExportService exportService() {
        return exportService;
    }

    @Override
    public void close() {
        liveAnalysisService.close();
        historicalAnalysisService.close();
        aggregateQueryService.close();
        exportService.close();
    }

    private static ReceiverProfile defaultReceiverProfile() {
        return new ReceiverProfile(
                new ReceiverProfileId("legacy-fixed-receiver"),
                "研究室受信局（移行値・要確認）",
                new GeoPosition(
                        34.718983358515715,
                        135.29057866131427),
                null,
                null,
                null,
                LocalDate.of(1900, 1, 1),
                null,
                "既存解析コードの座標。研究利用前に設備情報と照合する。");
    }
}
