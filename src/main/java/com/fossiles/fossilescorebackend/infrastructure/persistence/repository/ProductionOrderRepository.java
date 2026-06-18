package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderRepository extends JpaRepository<ProductionOrderEntity, Long> {
    Optional<ProductionOrderEntity> findByCode(String code);
    boolean existsByCode(String code);
    List<ProductionOrderEntity> findByOrderType(String orderType);
    List<ProductionOrderEntity> findByStatus(String status);
    List<ProductionOrderEntity> findByCustomerId(Long customerId);
    Optional<ProductionOrderEntity> findByDistributionId(Long distributionId);

    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.status IN :statuses ORDER BY po.deliveryDate ASC, po.createdAt ASC")
    List<ProductionOrderEntity> findByStatusIn(@Param("statuses") List<String> statuses);

    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.status NOT IN ('CANCELLED') ORDER BY po.createdAt DESC")
    List<ProductionOrderEntity> findActiveOrders();

    @Query("SELECT po.vendorShipmentNumber FROM ProductionOrderEntity po WHERE po.vendorShipmentNumber IS NOT NULL")
    List<String> findAllVendorShipmentNumbers();

    boolean existsByVendorShipmentNumber(String vendorShipmentNumber);

    @Query("SELECT COUNT(po) > 0 FROM ProductionOrderEntity po WHERE po.vendorShipmentNumber = :num AND po.id <> :excludeId")
    boolean existsByVendorShipmentNumberAndIdNot(@Param("num") String num, @Param("excludeId") Long excludeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.id = :id")
    Optional<ProductionOrderEntity> findByIdForUpdate(@Param("id") Long id);
}

