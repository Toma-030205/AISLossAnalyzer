package ais.analysis;

import ais.domain.AnalysisProfile;
import ais.domain.PositionReport;
import ais.domain.VesselClass;

import java.util.Objects;

public final class SpecificationReportRatePolicy
        implements ReportRatePolicy {

    private final double classBCsHighSpeedIntervalSeconds;

    public SpecificationReportRatePolicy(AnalysisProfile profile) {
        Objects.requireNonNull(profile, "profile");
        classBCsHighSpeedIntervalSeconds =
                profile.classBCsHighSpeedIntervalSeconds();
    }

    @Override
    public double expectedIntervalSeconds(
            PositionReport report,
            boolean changingCourse) {
        Objects.requireNonNull(report, "report");

        if (report.vesselClass() == VesselClass.CLASS_A) {
            return classAInterval(report, changingCourse);
        }
        if (report.vesselClass() == VesselClass.CLASS_B) {
            return classBInterval(report, changingCourse);
        }

        throw new IllegalArgumentException(
                "unsupported vessel class: " + report.vesselClass());
    }

    private static double classAInterval(
            PositionReport report,
            boolean changingCourse) {
        boolean anchoredOrMoored =
                report.navigationStatus() != null
                        && (report.navigationStatus() == 1
                        || report.navigationStatus() == 5);

        if (report.sogKnots() == null) {
            return anchoredOrMoored ? 180.0 : 10.0;
        }

        double sog = report.sogKnots();

        if (anchoredOrMoored) {
            return sog <= 3.0 ? 180.0 : 10.0;
        }
        if (sog <= 14.0) {
            return changingCourse ? 10.0 / 3.0 : 10.0;
        }
        if (sog <= 23.0) {
            return changingCourse ? 2.0 : 6.0;
        }
        return 2.0;
    }

    private double classBInterval(
            PositionReport report,
            boolean changingCourse) {
        if (report.sogKnots() == null
                || report.sogKnots() <= 2.0) {
            return 180.0;
        }

        double sog = report.sogKnots();

        if (report.isClassBCarrierSense()) {
            return sog <= 14.0
                    ? 30.0
                    : classBCsHighSpeedIntervalSeconds;
        }
        if (sog <= 14.0) {
            return 30.0;
        }
        if (sog <= 23.0) {
            return changingCourse ? 5.0 : 15.0;
        }
        return 5.0;
    }
}
