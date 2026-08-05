package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
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
            + "AND s.hardwareCondition = :hardwareCondition "
            + "ORDER BY s.id ASC")
    List<KioscoStockEntity> findAllForUpdateByHardware(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("hardwareCondition") String hardwareCondition
    );

    /**
     * Bloquea la fila de stock por herraje. Si hay duplicados (p. ej. color_id NULL
     * con UNIQUE clásico de Postgres), toma la fila más antigua.
     */
    default Optional<KioscoStockEntity> findForUpdateByHardware(
            Long locationId, Long productId, Long colorId, String hardwareCondition
    ) {
        List<KioscoStockEntity> rows = findAllForUpdateByHardware(
                locationId, productId, colorId, hardwareCondition);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Inserta solo si no hay fila equivalente. Usa IS NOT DISTINCT FROM para
     * color_id NULL (ON CONFLICT clásico de Postgres no protege NULLs).
     */
    @Modifying
    @Query(value = """
            INSERT INTO kiosco_stock (
                location_id, product_id, color_id, hardware_condition,
                current_stock, minimum_stock, last_updated_at, created_at, updated_at,
                created_by, updated_by
            )
            SELECT
                :locationId, :productId, :colorId, :hardwareCondition,
                0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                :userId, :userId
            WHERE NOT EXISTS (
                SELECT 1
                FROM kiosco_stock s
                WHERE s.location_id = :locationId
                  AND s.product_id = :productId
                  AND s.color_id IS NOT DISTINCT FROM :colorId
                  AND s.hardware_condition = :hardwareCondition
            )
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId,
            @Param("userId") Long userId,
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
