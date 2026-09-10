package ais.analysis;

import ais.domain.PositionReport;

@FunctionalInterface
public interface ReportRatePolicy {

    double expectedIntervalSeconds(
            PositionReport report,
            boolean changingCourse);
}
