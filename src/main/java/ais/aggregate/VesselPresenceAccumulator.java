package ais.aggregate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class VesselPresenceAccumulator<S> {

    private final Map<AggregateKey<S>, Set<Integer>> vessels =
            new HashMap<>();

    public void add(AggregateKey<S> key, int mmsi) {
        vessels.computeIfAbsent(key, ignored -> new HashSet<>()).add(mmsi);
    }

    public Set<Integer> vesselsFor(AggregateKey<S> key) {
        return Set.copyOf(vessels.getOrDefault(key, Set.of()));
    }

    public void clear() {
        vessels.clear();
    }
}
