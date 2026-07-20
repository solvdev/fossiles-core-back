package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoInternalCountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoInternalCountRepository extends JpaRepository<KioscoInternalCountEntity, Long> {

    Optional<KioscoInternalCountEntity> findByLocationIdAndCountDate(Long locationId, LocalDate countDate);

    List<KioscoInternalCountEntity> findByLocationIdOrderByCountDateDescSavedAtDesc(Long locationId);
}
