package ais.storage;

import ais.domain.AnalysisProfile;
import ais.domain.AnalysisProfileId;

import java.util.List;
import java.util.Optional;

public interface AnalysisProfileRepository {

    void save(AnalysisProfile profile);

    Optional<AnalysisProfile> findById(AnalysisProfileId id);

    List<AnalysisProfile> findAll();
}
