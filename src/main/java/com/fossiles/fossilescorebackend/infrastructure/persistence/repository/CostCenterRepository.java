package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CostCenterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CostCenterRepository extends JpaRepository<CostCenterEntity, Long> {
    Optional<CostCenterEntity> findByCode(String code);
    boolean existsByCode(String code);
}

