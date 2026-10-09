package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPeriodConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KioskPeriodConfigRepository
        extends JpaRepository<KioskPeriodConfigEntity, KioskPeriodConfigEntity.Key> {

    List<KioskPeriodConfigEntity> findByPeriodYear(Integer periodYear);

    List<KioskPeriodConfigEntity> findByPeriodYearAndPeriodMonth(Integer periodYear, Integer periodMonth);
}
