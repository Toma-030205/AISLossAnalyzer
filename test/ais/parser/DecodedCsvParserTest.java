package ais.parser;

import ais.model.AisMessage;

import java.time.LocalDateTime;
import java.util.Arrays;

public class DecodedCsvParserTest {

    public static void main(String[] args) {

        parsesClassAPositionReport();
        parsesType5ShipLength();
        rejectsUnsupportedMessageType();

        System.out.println(
                "DecodedCsvParserTest: PASS");
    }

    private static void parsesClassAPositionReport() {

        AisMessage message =
                DecodedCsvParser.decode(
                        "07/06/01,00:00:00,1,0,477035000,"
                                + "0,-0.0,15.3,0,135.281295,34.702950,"
                                + "259.8,262,0,0,0,2,0:4:1");

        require(message != null, "Class A message");
        require(message.messageType == 1, "message type");
        require(message.mmsi == 477035000, "MMSI");
        require(
                LocalDateTime.of(
                        2007, 6, 1, 0, 0)
                        .equals(message.timestamp),
                "timestamp");
        requireClose(
                135.281295,
                message.lon,
                "longitude");
        requireClose(
                34.702950,
                message.lat,
                "latitude");
        requireClose(
                15.3,
                message.sog,
                "SOG");
        requireClose(
                259.8,
                message.cog,
                "COG");
        requireClose(
                262.0,
                message.trueHeading,
                "heading");
    }

    private static void parsesType5ShipLength() {

        String[] columns =
                new String[32];

        Arrays.fill(columns, "");
        columns[0] = "07/06/01";
        columns[1] = "00:00:16";
        columns[2] = "5";
        columns[3] = "0";
        columns[4] = "431400945";
        columns[18] = "0";
        columns[19] = "0";
        columns[20] = "JK5571";
        columns[21] = "KAMIHARUMARU";
        columns[22] = "70";
        columns[23] = "35";
        columns[24] = "15";
        columns[25] = "4";
        columns[26] = "3";
        columns[27] = "1";
        columns[28] = "03/01";
        columns[29] = "00:30";
        columns[30] = "5.2";
        columns[31] = "KOBE";

        AisMessage message =
                DecodedCsvParser.decode(
                        String.join(",", columns));

        require(message != null, "Type 5 message");
        require(message.messageType == 5, "Type 5 type");
        require(message.shipLength != null, "ship length exists");
        require(message.shipLength == 50, "ship length");
    }

    private static void rejectsUnsupportedMessageType() {

        AisMessage message =
                DecodedCsvParser.decode(
                        "07/06/01,00:00:00,10,0,477035000");

        require(message == null, "unsupported type");
    }

    private static void requireClose(
            double expected,
            Double actual,
            String label) {

        require(actual != null, label + " exists");
        require(
                Math.abs(expected - actual) < 0.000001,
                label);
    }

    private static void require(
            boolean condition,
            String label) {

        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
