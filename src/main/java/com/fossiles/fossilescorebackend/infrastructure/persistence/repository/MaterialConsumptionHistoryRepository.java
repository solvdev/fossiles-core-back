package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialConsumptionHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialConsumptionHistoryRepository extends JpaRepository<MaterialConsumptionHistoryEntity, Long> {
    
    List<MaterialConsumptionHistoryEntity> findByMaterialId(Long materialId);
    
    List<MaterialConsumptionHistoryEntity> findByMaterialIdAndConsumptionDateBetween(
            Long materialId, LocalDate startDate, LocalDate endDate);
    
    Optional<MaterialConsumptionHistoryEntity> findByMaterialIdAndConsumptionDate(
            Long materialId, LocalDate date);
    
    @Query("SELECT SUM(m.quantityConsumed) FROM MaterialConsumptionHistoryEntity m " +
           "WHERE m.materialId = :materialId AND m.consumptionDate BETWEEN :startDate AND :endDate")
    java.math.BigDecimal sumConsumptionByMaterialAndDateRange(
            @Param("materialId") Long materialId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
    
    @Query("SELECT AVG(m.quantityConsumed) FROM MaterialConsumptionHistoryEntity m " +
           "WHERE m.materialId = :materialId AND m.consumptionDate BETWEEN :startDate AND :endDate")
    Double averageConsumptionByMaterialAndDateRange(
            @Param("materialId") Long materialId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}

