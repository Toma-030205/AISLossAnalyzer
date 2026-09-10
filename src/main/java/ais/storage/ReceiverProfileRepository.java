package ais.storage;

import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReceiverProfileRepository {

    void save(ReceiverProfile profile);

    Optional<ReceiverProfile> findById(ReceiverProfileId id);

    Optional<ReceiverProfile> findEffectiveOn(LocalDate date);

    List<ReceiverProfile> findAll();
}
