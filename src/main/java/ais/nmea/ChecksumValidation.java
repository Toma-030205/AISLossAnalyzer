package ais.nmea;

public record ChecksumValidation(
        ChecksumStatus status,
        Integer expected,
        Integer actual) {

    public boolean isValid() {
        return status == ChecksumStatus.VALID;
    }
}
