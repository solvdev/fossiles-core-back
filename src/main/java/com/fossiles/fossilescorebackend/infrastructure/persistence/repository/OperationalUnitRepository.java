package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OperationalUnitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OperationalUnitRepository extends JpaRepository<OperationalUnitEntity, Long> {
    Optional<OperationalUnitEntity> findByCode(String code);
    boolean existsByCode(String code);
}

