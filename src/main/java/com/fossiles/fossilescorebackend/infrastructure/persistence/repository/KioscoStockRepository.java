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

    Optional<KioscoStockEntity> findByLocationIdAndProductIdAndColorId(Long locationId, Long productId, Long colorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM KioscoStockEntity s WHERE s.locationId = :locationId "
            + "AND s.productId = :productId "
            + "AND ((:colorId IS NULL AND s.colorId IS NULL) OR s.colorId = :colorId)")
    Optional<KioscoStockEntity> findForUpdate(
            @Param("locationId") Long locationId,
            @Param("productId") Long productId,
            @Param("colorId") Long colorId
    );

    List<KioscoStockEntity> findByLocationIdOrderByProductIdAscColorIdAsc(Long locationId);

    @Query("SELECT s FROM KioscoStockEntity s "
            + "WHERE s.locationId = :locationId AND s.currentStock <= s.minimumStock "
            + "ORDER BY s.currentStock ASC, s.productId ASC")
    List<KioscoStockEntity> findLowStockByLocation(@Param("locationId") Long locationId);

    @Query("SELECT s FROM KioscoStockEntity s WHERE s.locationId IN :locationIds")
    List<KioscoStockEntity> findByLocationIdIn(@Param("locationIds") List<Long> locationIds);
}
