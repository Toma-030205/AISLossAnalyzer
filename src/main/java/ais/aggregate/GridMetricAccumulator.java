package ais.aggregate;

import ais.domain.VesselClass;
import ais.spatial.GridCellId;

import java.time.Instant;
import java.util.Map;

public final class GridMetricAccumulator {

    private final SparseMetricAccumulator<GridCellId> delegate =
            new SparseMetricAccumulator<>();

    public void addObserved(
            Instant bucketStart,
            GridCellId cell,
            VesselClass vesselClass,
            int mmsi) {
        delegate.addObserved(bucketStart, cell, vesselClass, mmsi);
    }

    public void addMissing(
            Instant bucketStart,
            GridCellId cell,
            VesselClass vesselClass,
            int mmsi) {
        delegate.addMissing(bucketStart, cell, vesselClass, mmsi);
    }

    public void addDuration(
            Instant bucketStart,
            GridCellId cell,
            VesselClass vesselClass,
            int mmsi,
            double observedSeconds,
            double staleSeconds) {
        delegate.addDuration(
                bucketStart,
                cell,
                vesselClass,
                mmsi,
                observedSeconds,
                staleSeconds);
    }

    public Map<AggregateKey<GridCellId>, AggregateMetric> snapshot() {
        return delegate.snapshot();
    }

    public void clear() {
        delegate.clear();
    }
}
