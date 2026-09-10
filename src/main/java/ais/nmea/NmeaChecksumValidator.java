package ais.nmea;

public final class NmeaChecksumValidator {

    public ChecksumValidation validate(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return new ChecksumValidation(
                    ChecksumStatus.MALFORMED,
                    null,
                    null);
        }

        String value = sentence.trim();
        int checksumMarker = value.lastIndexOf('*');

        if (checksumMarker < 0) {
            return new ChecksumValidation(
                    ChecksumStatus.MISSING,
                    null,
                    null);
        }
        if (value.charAt(0) != '!'
                || checksumMarker + 3 != value.length()) {
            return new ChecksumValidation(
                    ChecksumStatus.MALFORMED,
                    null,
                    null);
        }

        int expected;
        try {
            expected = Integer.parseInt(
                    value.substring(checksumMarker + 1),
                    16);
        } catch (NumberFormatException exception) {
            return new ChecksumValidation(
                    ChecksumStatus.MALFORMED,
                    null,
                    null);
        }

        int actual = 0;
        for (int index = 1; index < checksumMarker; index++) {
            actual ^= value.charAt(index);
        }

        return new ChecksumValidation(
                actual == expected
                        ? ChecksumStatus.VALID
                        : ChecksumStatus.INVALID,
                expected,
                actual);
    }
}
