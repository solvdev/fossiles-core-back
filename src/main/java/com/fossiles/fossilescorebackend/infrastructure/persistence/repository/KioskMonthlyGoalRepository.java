package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskMonthlyGoalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioskMonthlyGoalRepository extends JpaRepository<KioskMonthlyGoalEntity, Long> {
    Optional<KioskMonthlyGoalEntity> findByKioskLocationIdAndGoalYearAndGoalMonth(
            Long kioskLocationId,
            Integer goalYear,
            Integer goalMonth
    );

    List<KioskMonthlyGoalEntity> findByKioskLocationIdInAndGoalYearAndGoalMonth(
            List<Long> kioskLocationIds,
            Integer goalYear,
            Integer goalMonth
    );

    List<KioskMonthlyGoalEntity> findByKioskLocationIdOrderByGoalYearDescGoalMonthDesc(Long kioskLocationId);
}
