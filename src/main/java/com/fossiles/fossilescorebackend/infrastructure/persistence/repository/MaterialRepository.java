package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialRepository extends JpaRepository<MaterialEntity, Long> {
    Optional<MaterialEntity> findBySku(String sku);
    boolean existsBySku(String sku);
    
    @Query("SELECT m FROM MaterialEntity m WHERE " +
           "LOWER(m.sku) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(m.name) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<MaterialEntity> searchBySkuOrName(@Param("query") String query);
    
    @Query("SELECT m FROM MaterialEntity m WHERE " +
           "(LOWER(m.sku) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(m.name) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "m.status = 'active'")
    List<MaterialEntity> searchActiveBySkuOrName(@Param("query") String query);
}

