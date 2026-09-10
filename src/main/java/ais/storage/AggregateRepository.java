package ais.storage;

import ais.aggregate.AggregationSnapshot;
import ais.domain.AnalysisRunId;

public interface AggregateRepository {

    void replaceRunAggregates(AnalysisRunId runId, AggregateBatch batch);

    AggregationSnapshot query(AggregateQuery query);
}
