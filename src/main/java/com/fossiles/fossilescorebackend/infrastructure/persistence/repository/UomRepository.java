package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UomEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UomRepository extends JpaRepository<UomEntity, Long> {
    Optional<UomEntity> findByCode(String code);
    boolean existsByCode(String code);
}

