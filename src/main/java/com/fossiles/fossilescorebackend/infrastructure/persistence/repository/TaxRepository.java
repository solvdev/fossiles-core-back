package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxRepository extends JpaRepository<TaxEntity, Long> {
    Optional<TaxEntity> findByCode(String code);
    boolean existsByCode(String code);
    List<TaxEntity> findByStatus(String status);
}

