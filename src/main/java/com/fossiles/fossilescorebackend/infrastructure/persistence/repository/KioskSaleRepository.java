package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface KioskSaleRepository extends JpaRepository<KioskSaleEntity, Long> {
    long countBySaleDate(LocalDate saleDate);
    List<KioskSaleEntity> findByKioskLocationIdOrderBySoldAtDesc(Long kioskLocationId);
    List<KioskSaleEntity> findByKioskLocationIdAndSaleDateBetweenOrderBySoldAtDesc(
            Long kioskLocationId,
            LocalDate startDate,
            LocalDate endDate
    );
    List<KioskSaleEntity> findBySaleDateBetweenOrderBySoldAtDesc(LocalDate startDate, LocalDate endDate);
}
