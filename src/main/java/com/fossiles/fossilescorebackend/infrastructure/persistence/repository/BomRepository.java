package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BomRepository extends JpaRepository<BomEntity, Long> {
    List<BomEntity> findByProductId(Long productId);

    @Query("SELECT b FROM BomEntity b WHERE b.productId = :productId AND b.status = :status ORDER BY b.id")
    List<BomEntity> findByProductIdAndStatus(@Param("productId") Long productId, @Param("status") String status);

    @Query("SELECT b FROM BomEntity b WHERE b.productId IN :productIds AND b.status = :status ORDER BY b.id")
    List<BomEntity> findByProductIdInAndStatus(@Param("productIds") Collection<Long> productIds, @Param("status") String status);
}

