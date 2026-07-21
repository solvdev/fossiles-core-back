package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KioscoStockRepository extends JpaRepository<KioscoStockEntity, Long> {

    Optional<KioscoStockEntity> findFirstByLocationIdAndProductIdAndColorIdOrderByHardwareConditionAsc(
            Long locationId, Long productId, Long colorId);

    List<KioscoStockEntity> findByLocationIdAndProductIdAndColorIdOrderByHardwareConditionAsc(
            Long locationId, Long productId, Long colorId);

    /** Prefer NUEVO when multiple filas de herraje existen. */
    default Optional<KioscoStockEntity> findByLocationIdAndProductIdAndColorId(
            Long locationId, Long productId, Long colorId
    ) {
        return findFirstByLocationIdAndProductIdAndColorIdOrderByHardwareConditionAsc(
                locationId, productId, colorId);
    }

    Optional<KioscoStockEntity> findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
            Long locationId, Long productId, Long colorId, String hardwareCondition);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM KioscoStockEntity s WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId) "
            + "ORDER BY s.hardwareCondition ASC")
    List<KioscoStockEntity> findAllForUpdate(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId
    );

    /** Prefer NUEVO when multiple filas de herraje existen (legado). */
    default Optional<KioscoStockEntity> findForUpdate(
            Long locationId, Long productId, Long colorId
    ) {
        List<KioscoStockEntity> rows = findAllForUpdate(locationId, productId, colorId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM KioscoStockEntity s WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId) "
            + "AND s.hardwareCondition = :hardwareCondition")
    Optional<KioscoStockEntity> findForUpdateByHardware(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("hardwareCondition") String hardwareCondition
    );

    List<KioscoStockEntity> findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(Long locationId);

    @Query("SELECT s FROM KioscoStockEntity s "
            + "WHERE s.locationId = :locationId AND s.currentStock <= s.minimumStock "
            + "ORDER BY s.currentStock ASC, s.productId ASC")
    List<KioscoStockEntity> findLowStockByLocation(@Param("locationId") Long locationId);

    @Query("SELECT s FROM KioscoStockEntity s WHERE s.locationId IN :locationIds")
    List<KioscoStockEntity> findByLocationIdIn(@Param("locationIds") List<Long> locationIds);
}
