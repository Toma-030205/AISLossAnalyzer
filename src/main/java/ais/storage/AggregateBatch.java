package ais.storage;

import ais.aggregate.AggregationSnapshot;

import java.util.Objects;

public record AggregateBatch(AggregationSnapshot snapshot) {

    public AggregateBatch {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
