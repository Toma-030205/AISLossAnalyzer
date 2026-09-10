package ais.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

final class JdbcSupport {

    private static final DateTimeFormatter INSTANT_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private JdbcSupport() {
    }

    static void setNullableString(PreparedStatement statement, int index,
                                  Object value) throws SQLException {
        statement.setString(index, value == null ? null
                : value instanceof Instant instant ? instant(instant)
                : value.toString());
    }

    static String instant(Instant value) {
        return INSTANT_FORMATTER.format(value);
    }

    static void setNullableInteger(PreparedStatement statement, int index,
                                   Integer value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    static void setNullableDouble(PreparedStatement statement, int index,
                                  Double value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setDouble(index, value);
        }
    }

    static Integer nullableInteger(ResultSet results, String column)
            throws SQLException {
        int value = results.getInt(column);
        return results.wasNull() ? null : value;
    }

    static Double nullableDouble(ResultSet results, String column)
            throws SQLException {
        double value = results.getDouble(column);
        return results.wasNull() ? null : value;
    }

    static Instant nullableInstant(ResultSet results, String column)
            throws SQLException {
        String value = results.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    static LocalDate nullableDate(ResultSet results, String column)
            throws SQLException {
        String value = results.getString(column);
        return value == null ? null : LocalDate.parse(value);
    }
}
