package ais.app;

public record SimulationDiagnostics(
        long processedTransmissionCount,
        long receivedCount,
        long lostCount,
        long outOfModelCount,
        long metadataUpdateCount) {

    public SimulationDiagnostics {
        if (processedTransmissionCount < 0 || receivedCount < 0
                || lostCount < 0 || outOfModelCount < 0
                || metadataUpdateCount < 0
                || processedTransmissionCount
                != receivedCount + lostCount + outOfModelCount) {
            throw new IllegalArgumentException(
                    "invalid simulation diagnostic counts");
        }
    }
}
