package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KioscoMovementRepository extends JpaRepository<KioscoMovementEntity, Long> {

    List<KioscoMovementEntity> findByKioscoStockIdOrderByCreatedAtDescIdDesc(Long kioscoStockId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<KioscoMovementEntity> findByLocationIdOrderByCreatedAtDesc(@Param("locationId") Long locationId);

    @Query("SELECT m FROM KioscoMovementEntity m "
            + "JOIN KioscoStockEntity s ON s.id = m.kioscoStockId "
            + "WHERE s.locationId = :locationId "
            + "AND (:productId IS NULL OR s.productId = :productId) "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR :colorId IS NULL OR s.colorId = :colorId) "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<KioscoMovementEntity> findByLocationAndFilters(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId
    );

    boolean existsByKioscoStockIdAndMovementTypeAndReferenceIdAndUserIdAndQuantityAndAffectsStock(
            Long kioscoStockId,
            KioscoMovementType movementType,
            Long referenceId,
            Long userId,
            Integer quantity,
            Boolean affectsStock
    );
}
