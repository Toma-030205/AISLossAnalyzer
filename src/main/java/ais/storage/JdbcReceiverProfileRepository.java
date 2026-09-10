package ais.storage;

import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcReceiverProfileRepository
        implements ReceiverProfileRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, name, latitude, longitude, antenna_height_meters,
                   antenna_model, receiver_model, valid_from, valid_to, notes
            FROM receiver_profile
            """;

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public JdbcReceiverProfileRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = new TransactionRunner(database);
    }

    @Override
    public void save(ReceiverProfile profile) {
        Objects.requireNonNull(profile, "profile");
        transactions.execute(connection -> {
            rejectOverlappingPeriod(connection, profile);
            upsert(connection, profile);
            return null;
        });
    }

    @Override
    public Optional<ReceiverProfile> findById(ReceiverProfileId id) {
        Objects.requireNonNull(id, "id");
        return queryOne(SELECT_COLUMNS + " WHERE id = ?", id.value());
    }

    @Override
    public Optional<ReceiverProfile> findEffectiveOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return queryOne(SELECT_COLUMNS + """
                 WHERE valid_from <= ?
                   AND (valid_to IS NULL OR valid_to >= ?)
                 ORDER BY valid_from DESC
                 LIMIT 1
                """, date.toString(), date.toString());
    }

    @Override
    public List<ReceiverProfile> findAll() {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        SELECT_COLUMNS + " ORDER BY valid_from, id");
                ResultSet results = statement.executeQuery()) {
            List<ReceiverProfile> profiles = new ArrayList<>();
            while (results.next()) {
                profiles.add(map(results));
            }
            return List.copyOf(profiles);
        } catch (SQLException failure) {
            throw new StorageException("could not read receiver profiles",
                    failure);
        }
    }

    static void upsert(Connection connection, ReceiverProfile profile)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO receiver_profile (
                    id, name, latitude, longitude, antenna_height_meters,
                    antenna_model, receiver_model, valid_from, valid_to, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    latitude = excluded.latitude,
                    longitude = excluded.longitude,
                    antenna_height_meters = excluded.antenna_height_meters,
                    antenna_model = excluded.antenna_model,
                    receiver_model = excluded.receiver_model,
                    valid_from = excluded.valid_from,
                    valid_to = excluded.valid_to,
                    notes = excluded.notes
                """)) {
            statement.setString(1, profile.id().value());
            statement.setString(2, profile.name());
            statement.setDouble(3, profile.position().latitude());
            statement.setDouble(4, profile.position().longitude());
            JdbcSupport.setNullableDouble(statement, 5,
                    profile.antennaHeightMeters());
            statement.setString(6, profile.antennaModel());
            statement.setString(7, profile.receiverModel());
            statement.setString(8, profile.validFrom().toString());
            JdbcSupport.setNullableString(statement, 9, profile.validTo());
            statement.setString(10, profile.notes());
            statement.executeUpdate();
        }
    }

    private void rejectOverlappingPeriod(Connection connection,
                                         ReceiverProfile profile)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM receiver_profile
                WHERE id <> ?
                  AND (valid_to IS NULL OR valid_to >= ?)
                  AND (? IS NULL OR valid_from <= ?)
                LIMIT 1
                """)) {
            statement.setString(1, profile.id().value());
            statement.setString(2, profile.validFrom().toString());
            JdbcSupport.setNullableString(statement, 3, profile.validTo());
            JdbcSupport.setNullableString(statement, 4, profile.validTo());
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    throw new SQLException(
                            "receiver validity overlaps profile "
                                    + results.getString(1));
                }
            }
        }
    }

    private Optional<ReceiverProfile> queryOne(String sql,
                                               String... parameters) {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(map(results))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("could not read receiver profile",
                    failure);
        }
    }

    private static ReceiverProfile map(ResultSet results)
            throws SQLException {
        return new ReceiverProfile(
                new ReceiverProfileId(results.getString("id")),
                results.getString("name"),
                new GeoPosition(results.getDouble("latitude"),
                        results.getDouble("longitude")),
                JdbcSupport.nullableDouble(results,
                        "antenna_height_meters"),
                results.getString("antenna_model"),
                results.getString("receiver_model"),
                LocalDate.parse(results.getString("valid_from")),
                JdbcSupport.nullableDate(results, "valid_to"),
                results.getString("notes"));
    }
}
