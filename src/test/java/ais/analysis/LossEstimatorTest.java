package ais.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LossEstimatorTest {

    @ParameterizedTest
    @CsvSource({
            "361.0, 360.0, 0",
            "539.999, 360.0, 0",
            "540.0, 360.0, 1",
            "2.51, 2.0, 0",
            "3.0, 2.0, 1",
            "100.0, 10.0, 9"
    })
    void estimatesMissingMessagesFromRawIntervals(
            double actual,
            double expected,
            long missing) {
        assertEquals(
                missing,
                LossEstimator.estimateMissingMessages(actual, expected));
    }

    @Test
    void rejectsInvalidIntervals() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LossEstimator.estimateMissingMessages(-1.0, 10.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LossEstimator.estimateMissingMessages(1.0, 0.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LossEstimator.estimateMissingMessages(
                        Double.NaN,
                        10.0));
    }
}
