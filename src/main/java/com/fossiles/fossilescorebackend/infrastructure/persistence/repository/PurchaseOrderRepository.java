package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PurchaseOrderEntity po where po.id = :id")
    Optional<PurchaseOrderEntity> findByIdForUpdate(@Param("id") Long id);
    Optional<PurchaseOrderEntity> findById(Long id);
    
    List<PurchaseOrderEntity> findByStatus(String status);
    
    boolean existsByCode(String code);
    
    @Query("SELECT po FROM PurchaseOrderEntity po WHERE po.orderDate BETWEEN :startDate AND :endDate")
    List<PurchaseOrderEntity> findByOrderDateBetween(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );
    
    long countByStatus(String status);
}
