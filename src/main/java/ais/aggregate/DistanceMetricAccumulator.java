package ais.aggregate;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.time.Instant;
import java.util.Map;

public final class DistanceMetricAccumulator {

    private final SparseMetricAccumulator<DistanceBand> delegate =
            new SparseMetricAccumulator<>();

    public void addObserved(
            Instant bucketStart,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi) {
        delegate.addObserved(bucketStart, band, vesselClass, mmsi);
    }

    public void addMissing(
            Instant bucketStart,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi) {
        delegate.addMissing(bucketStart, band, vesselClass, mmsi);
    }

    public void addDuration(
            Instant bucketStart,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi,
            double observedSeconds,
            double staleSeconds) {
        delegate.addDuration(
                bucketStart,
                band,
                vesselClass,
                mmsi,
                observedSeconds,
                staleSeconds);
    }

    public Map<AggregateKey<DistanceBand>, AggregateMetric> snapshot() {
        return delegate.snapshot();
    }

    public void clear() {
        delegate.clear();
    }
}
