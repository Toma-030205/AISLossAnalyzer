package ais.storage;

import ais.domain.VesselClass;
import ais.domain.VesselMetadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class JdbcVesselMetadataRepository
        implements VesselMetadataRepository {

    private static final String SELECT_COLUMNS = """
            SELECT mmsi, vessel_class, imo, call_sign, vessel_name,
                   ship_type, destination, ship_length_meters, valid_from
              FROM vessel_metadata_history
            """;

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public JdbcVesselMetadataRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = new TransactionRunner(database);
    }

    @Override
    public boolean saveIfChanged(VesselMetadata metadata,
                                 int sourceMessageType) {
        Objects.requireNonNull(metadata, "metadata");
        if (sourceMessageType != 5 && sourceMessageType != 24) {
            throw new IllegalArgumentException(
                    "metadata source must be AIS message type 5 or 24");
        }
        return transactions.execute(connection -> saveIfChanged(
                connection, metadata, sourceMessageType));
    }

    @Override
    public Optional<VesselMetadata> findEffectiveAt(int mmsi, Instant time) {
        Objects.requireNonNull(time, "time");
        return queryOne(SELECT_COLUMNS + """
                 WHERE mmsi = ? AND valid_from <= ?
                   AND (valid_to IS NULL OR valid_to > ?)
                 ORDER BY valid_from DESC LIMIT 1
                """, mmsi, JdbcSupport.instant(time),
                JdbcSupport.instant(time));
    }

    @Override
    public Optional<VesselMetadata> findLatest(int mmsi) {
        return queryOne(SELECT_COLUMNS + """
                 WHERE mmsi = ? ORDER BY valid_from DESC LIMIT 1
                """, mmsi);
    }

    static boolean saveIfChanged(Connection connection,
                                 VesselMetadata metadata,
                                 int sourceMessageType)
            throws SQLException {
        StoredMetadata exact = findExact(connection, metadata.mmsi(),
                metadata.updatedAt());
        if (exact != null) {
            if (sameValues(exact.metadata(), metadata)) {
                return false;
            }
            updateExact(connection, metadata, sourceMessageType);
            return true;
        }

        StoredMetadata previous = findPrevious(connection, metadata.mmsi(),
                metadata.updatedAt());
        if (previous != null
                && sameValues(previous.metadata(), metadata)) {
            return false;
        }
        Instant nextStart = findNextStart(connection, metadata.mmsi(),
                metadata.updatedAt());
        if (previous != null) {
            closePrevious(connection, previous.metadata().mmsi(),
                    previous.metadata().updatedAt(), metadata.updatedAt());
        }
        insert(connection, metadata, sourceMessageType, nextStart);
        return true;
    }

    private Optional<VesselMetadata> queryOne(String sql,
                                              Object... parameters) {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(map(results))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new StorageException("could not read vessel metadata",
                    failure);
        }
    }

    private static StoredMetadata findExact(Connection connection, int mmsi,
                                            Instant at) throws SQLException {
        return findStored(connection, SELECT_COLUMNS + """
                 WHERE mmsi = ? AND valid_from = ? LIMIT 1
                """, mmsi, JdbcSupport.instant(at));
    }

    private static StoredMetadata findPrevious(Connection connection, int mmsi,
                                               Instant at)
            throws SQLException {
        return findStored(connection, SELECT_COLUMNS + """
                 WHERE mmsi = ? AND valid_from < ?
                 ORDER BY valid_from DESC LIMIT 1
                """, mmsi, JdbcSupport.instant(at));
    }

    private static StoredMetadata findStored(Connection connection, String sql,
                                             Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? new StoredMetadata(map(results)) : null;
            }
        }
    }

    private static Instant findNextStart(Connection connection, int mmsi,
                                         Instant after) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT valid_from FROM vessel_metadata_history
                 WHERE mmsi = ? AND valid_from > ?
                 ORDER BY valid_from LIMIT 1
                """)) {
            statement.setInt(1, mmsi);
            statement.setString(2, JdbcSupport.instant(after));
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Instant.parse(results.getString(1)) : null;
            }
        }
    }

    private static void closePrevious(Connection connection, int mmsi,
                                      Instant previousStart, Instant end)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE vessel_metadata_history SET valid_to = ?
                 WHERE mmsi = ? AND valid_from = ?
                """)) {
            statement.setString(1, JdbcSupport.instant(end));
            statement.setInt(2, mmsi);
            statement.setString(3, JdbcSupport.instant(previousStart));
            statement.executeUpdate();
        }
    }

    private static void insert(Connection connection, VesselMetadata metadata,
                               int sourceMessageType, Instant validTo)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO vessel_metadata_history (
                    mmsi, valid_from, valid_to, source_message_type,
                    vessel_class, imo, call_sign, vessel_name, ship_type,
                    destination, ship_length_meters)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindMetadata(statement, metadata, sourceMessageType, validTo);
            statement.executeUpdate();
        }
    }

    private static void updateExact(Connection connection,
                                    VesselMetadata metadata,
                                    int sourceMessageType)
            throws SQLException {
        Instant existingEnd;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT valid_to FROM vessel_metadata_history
                 WHERE mmsi = ? AND valid_from = ?
                """)) {
            query.setInt(1, metadata.mmsi());
            query.setString(2, JdbcSupport.instant(metadata.updatedAt()));
            try (ResultSet results = query.executeQuery()) {
                existingEnd = results.next() && results.getString(1) != null
                        ? Instant.parse(results.getString(1)) : null;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE vessel_metadata_history SET
                    valid_to = ?, source_message_type = ?, vessel_class = ?,
                    imo = ?, call_sign = ?, vessel_name = ?, ship_type = ?,
                    destination = ?, ship_length_meters = ?
                 WHERE mmsi = ? AND valid_from = ?
                """)) {
            JdbcSupport.setNullableString(statement, 1, existingEnd);
            statement.setInt(2, sourceMessageType);
            statement.setString(3, metadata.vesselClass().name());
            JdbcSupport.setNullableInteger(statement, 4, metadata.imo());
            statement.setString(5, metadata.callSign());
            statement.setString(6, metadata.vesselName());
            JdbcSupport.setNullableInteger(statement, 7, metadata.shipType());
            statement.setString(8, metadata.destination());
            JdbcSupport.setNullableInteger(statement, 9,
                    metadata.shipLengthMeters());
            statement.setInt(10, metadata.mmsi());
            statement.setString(11,
                    JdbcSupport.instant(metadata.updatedAt()));
            statement.executeUpdate();
        }
    }

    private static void bindMetadata(PreparedStatement statement,
                                     VesselMetadata metadata,
                                     int sourceMessageType,
                                     Instant validTo) throws SQLException {
        statement.setInt(1, metadata.mmsi());
        statement.setString(2, JdbcSupport.instant(metadata.updatedAt()));
        JdbcSupport.setNullableString(statement, 3, validTo);
        statement.setInt(4, sourceMessageType);
        statement.setString(5, metadata.vesselClass().name());
        JdbcSupport.setNullableInteger(statement, 6, metadata.imo());
        statement.setString(7, metadata.callSign());
        statement.setString(8, metadata.vesselName());
        JdbcSupport.setNullableInteger(statement, 9, metadata.shipType());
        statement.setString(10, metadata.destination());
        JdbcSupport.setNullableInteger(statement, 11,
                metadata.shipLengthMeters());
    }

    private static VesselMetadata map(ResultSet results)
            throws SQLException {
        return new VesselMetadata(
                results.getInt("mmsi"),
                VesselClass.valueOf(results.getString("vessel_class")),
                JdbcSupport.nullableInteger(results, "imo"),
                results.getString("call_sign"),
                results.getString("vessel_name"),
                JdbcSupport.nullableInteger(results, "ship_type"),
                results.getString("destination"),
                JdbcSupport.nullableInteger(results, "ship_length_meters"),
                Instant.parse(results.getString("valid_from")));
    }

    private static boolean sameValues(VesselMetadata left,
                                      VesselMetadata right) {
        return left.mmsi() == right.mmsi()
                && left.vesselClass() == right.vesselClass()
                && Objects.equals(left.imo(), right.imo())
                && Objects.equals(left.callSign(), right.callSign())
                && Objects.equals(left.vesselName(), right.vesselName())
                && Objects.equals(left.shipType(), right.shipType())
                && Objects.equals(left.destination(), right.destination())
                && Objects.equals(left.shipLengthMeters(),
                        right.shipLengthMeters());
    }

    private record StoredMetadata(VesselMetadata metadata) {
    }
}
