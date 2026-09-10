package ais.regression;

import ais.logic.AisCoreCalculationTest;
import ais.parser.DecodedCsvParserTest;
import ais.stats.AisStatisticsTest;
import org.junit.jupiter.api.Test;

class LegacySmokeTest {

    @Test
    void existingCoreCalculationChecksStillPass() {
        AisCoreCalculationTest.main(new String[0]);
    }

    @Test
    void existingStatisticsChecksStillPass() {
        AisStatisticsTest.main(new String[0]);
    }

    @Test
    void existingDecodedCsvChecksStillPass() {
        DecodedCsvParserTest.main(new String[0]);
    }
}
