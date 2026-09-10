package ais.nmea;

import ais.input.InputDiagnosticCode;
import ais.input.ReceivedNmea;

import java.util.Set;

public final class NmeaEnvelopeParser {

    private static final Set<String> SUPPORTED_FORMATTERS =
            Set.of("!AIVDM", "!AIVDO");

    public NmeaFragment parse(ReceivedNmea record)
            throws NmeaParseException {
        String sentence = record.sentence();
        int checksumMarker = sentence.lastIndexOf('*');
        String body = checksumMarker < 0
                ? sentence
                : sentence.substring(0, checksumMarker);

        String[] fields = body.split(",", -1);

        if (fields.length != 7) {
            throw new NmeaParseException(
                    InputDiagnosticCode.INVALID_NMEA_FORMAT,
                    "AIS NMEA sentence must contain seven fields");
        }
        if (!SUPPORTED_FORMATTERS.contains(fields[0])) {
            throw new NmeaParseException(
                    InputDiagnosticCode.UNSUPPORTED_NMEA_SENTENCE,
                    "only !AIVDM and !AIVDO are supported");
        }

        int total = parseInteger(fields[1], "total fragment count");
        int number = parseInteger(fields[2], "fragment number");
        int fillBits = parseInteger(fields[6], "fill bits");

        if (total < 1 || total > 9
                || number < 1 || number > total) {
            throw new NmeaParseException(
                    InputDiagnosticCode.INVALID_FRAGMENT,
                    "fragment number is outside the declared range");
        }
        if (total == 1 && number != 1) {
            throw new NmeaParseException(
                    InputDiagnosticCode.INVALID_FRAGMENT,
                    "single-fragment sentence must have fragment number 1");
        }
        if (fillBits < 0 || fillBits > 5
                || (number < total && fillBits != 0)) {
            throw new NmeaParseException(
                    InputDiagnosticCode.INVALID_FRAGMENT,
                    "fill bits must be 0 to 5 and only appear on the final fragment");
        }
        if (fields[5].isEmpty()) {
            throw new NmeaParseException(
                    InputDiagnosticCode.INVALID_NMEA_FORMAT,
                    "AIS payload is empty");
        }

        return new NmeaFragment(
                record.receivedAt(),
                record.sequence(),
                record.source(),
                fields[0],
                total,
                number,
                fields[3],
                fields[4],
                fields[5],
                fillBits);
    }

    private static int parseInteger(String value, String label)
            throws NmeaParseException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new NmeaParseException(
                    InputDiagnosticCode.INVALID_NMEA_FORMAT,
                    label + " is not an integer");
        }
    }
}
