package ais.app;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AisLossAnalyzerApplicationTest {

    @Test
    void parsesHeadlessHistoricalBatchRange() {
        AisLossAnalyzerApplication.Options options =
                AisLossAnalyzerApplication.Options.parse(new String[]{
                        "--batch-from", "2025-11-01",
                        "--batch-to", "2025-12-31"});

        assertTrue(options.batchMode());
        assertFalse(options.validateOnly());
        assertEquals(LocalDate.of(2025, 11, 1), options.batchFrom());
        assertEquals(LocalDate.of(2025, 12, 31), options.batchTo());
    }

    @Test
    void rejectsIncompleteInvalidOrReversedBatchRange() {
        assertThrows(IllegalArgumentException.class,
                () -> AisLossAnalyzerApplication.Options.parse(
                        new String[]{"--batch-from", "2025-11-01"}));
        assertThrows(IllegalArgumentException.class,
                () -> AisLossAnalyzerApplication.Options.parse(
                        new String[]{
                                "--batch-from", "2025/11/01",
                                "--batch-to", "2025-12-31"}));
        assertThrows(IllegalArgumentException.class,
                () -> AisLossAnalyzerApplication.Options.parse(
                        new String[]{
                                "--batch-from", "2025-12-31",
                                "--batch-to", "2025-11-01"}));
        assertThrows(IllegalArgumentException.class,
                () -> AisLossAnalyzerApplication.Options.parse(
                        new String[]{
                                "--validate-only",
                                "--batch-from", "2025-11-01",
                                "--batch-to", "2025-12-31"}));
    }
}
