package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MonthlyLiquidationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MonthlyLiquidationRepository extends JpaRepository<MonthlyLiquidationEntity, Long> {

    Optional<MonthlyLiquidationEntity> findByLiquidationYearAndLiquidationMonth(Integer year, Integer month);
}

