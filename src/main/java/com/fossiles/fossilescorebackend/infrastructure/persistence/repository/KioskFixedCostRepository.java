package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KioskFixedCostRepository
        extends JpaRepository<KioskFixedCostEntity, KioskFixedCostEntity.Key> {

    List<KioskFixedCostEntity> findByPeriodYear(Integer periodYear);

    List<KioskFixedCostEntity> findByPeriodYearAndPeriodMonth(Integer periodYear, Integer periodMonth);
}
