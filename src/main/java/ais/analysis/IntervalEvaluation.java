package ais.analysis;

import java.util.Objects;

public sealed interface IntervalEvaluation
        permits IntervalEvaluation.Accepted, IntervalEvaluation.Excluded {

    record Accepted(IntervalCandidate interval)
            implements IntervalEvaluation {

        public Accepted {
            Objects.requireNonNull(interval, "interval");
        }
    }

    record Excluded(IntervalExclusionReason reason)
            implements IntervalEvaluation {

        public Excluded {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
