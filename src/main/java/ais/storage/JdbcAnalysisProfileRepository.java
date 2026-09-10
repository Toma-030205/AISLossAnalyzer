package ais.storage;

import ais.domain.AnalysisProfile;
import ais.domain.AnalysisProfileId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcAnalysisProfileRepository
        implements AnalysisProfileRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, rules_version, freshness_multiplier,
                   grid_size_meters, grid_origin_easting,
                   grid_origin_northing, distance_bin_kilometers,
                   maximum_distance_kilometers, minimum_expected_count,
                   minimum_distinct_vessels, track_gap_duration,
                   maximum_distance_jump_kilometers,
                   class_b_cs_high_speed_interval_seconds
            FROM analysis_profile
            """;

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public JdbcAnalysisProfileRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = new TransactionRunner(database);
    }

    @Override
    public void save(AnalysisProfile profile) {
        Objects.requireNonNull(profile, "profile");
        transactions.execute(connection -> {
            upsert(connection, profile);
            return null;
        });
    }

    @Override
    public Optional<AnalysisProfile> findById(AnalysisProfileId id) {
        Objects.requireNonNull(id, "id");
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        SELECT_COLUMNS + " WHERE id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(map(results))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("could not read analysis profile",
                    failure);
        }
    }

    @Override
    public List<AnalysisProfile> findAll() {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        SELECT_COLUMNS + " ORDER BY id");
                ResultSet results = statement.executeQuery()) {
            List<AnalysisProfile> profiles = new ArrayList<>();
            while (results.next()) {
                profiles.add(map(results));
            }
            return List.copyOf(profiles);
        } catch (SQLException failure) {
            throw new StorageException("could not read analysis profiles",
                    failure);
        }
    }

    static void upsert(Connection connection, AnalysisProfile profile)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_profile (
                    id, rules_version, freshness_multiplier,
                    grid_size_meters, grid_origin_easting,
                    grid_origin_northing, distance_bin_kilometers,
                    maximum_distance_kilometers, minimum_expected_count,
                    minimum_distinct_vessels, track_gap_duration,
                    maximum_distance_jump_kilometers,
                    class_b_cs_high_speed_interval_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    rules_version = excluded.rules_version,
                    freshness_multiplier = excluded.freshness_multiplier,
                    grid_size_meters = excluded.grid_size_meters,
                    grid_origin_easting = excluded.grid_origin_easting,
                    grid_origin_northing = excluded.grid_origin_northing,
                    distance_bin_kilometers = excluded.distance_bin_kilometers,
                    maximum_distance_kilometers = excluded.maximum_distance_kilometers,
                    minimum_expected_count = excluded.minimum_expected_count,
                    minimum_distinct_vessels = excluded.minimum_distinct_vessels,
                    track_gap_duration = excluded.track_gap_duration,
                    maximum_distance_jump_kilometers = excluded.maximum_distance_jump_kilometers,
                    class_b_cs_high_speed_interval_seconds = excluded.class_b_cs_high_speed_interval_seconds
                """)) {
            statement.setString(1, profile.id().value());
            statement.setString(2, profile.rulesVersion());
            statement.setDouble(3, profile.freshnessMultiplier());
            statement.setInt(4, profile.gridSizeMeters());
            statement.setDouble(5, profile.gridOriginEasting());
            statement.setDouble(6, profile.gridOriginNorthing());
            statement.setInt(7, profile.distanceBinKilometers());
            statement.setInt(8, profile.maximumDistanceKilometers());
            statement.setInt(9, profile.minimumExpectedCount());
            statement.setInt(10, profile.minimumDistinctVessels());
            statement.setString(11, profile.trackGapThreshold().toString());
            statement.setDouble(12,
                    profile.maximumDistanceJumpKilometers());
            statement.setDouble(13,
                    profile.classBCsHighSpeedIntervalSeconds());
            statement.executeUpdate();
        }
    }

    private static AnalysisProfile map(ResultSet results)
            throws SQLException {
        return new AnalysisProfile(
                new AnalysisProfileId(results.getString("id")),
                results.getString("rules_version"),
                results.getDouble("freshness_multiplier"),
                results.getInt("grid_size_meters"),
                results.getDouble("grid_origin_easting"),
                results.getDouble("grid_origin_northing"),
                results.getInt("distance_bin_kilometers"),
                results.getInt("maximum_distance_kilometers"),
                results.getInt("minimum_expected_count"),
                results.getInt("minimum_distinct_vessels"),
                Duration.parse(results.getString("track_gap_duration")),
                results.getDouble("maximum_distance_jump_kilometers"),
                results.getDouble(
                        "class_b_cs_high_speed_interval_seconds"));
    }
}
