package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationInternalNumberSequenceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocationInternalNumberSequenceRepository extends JpaRepository<LocationInternalNumberSequenceEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LocationInternalNumberSequenceEntity> findWithLockBySeriesCode(String seriesCode);
}
