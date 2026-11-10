package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionOrderPartialReleaseLineRepository extends JpaRepository<ProductionOrderPartialReleaseLineEntity, Long> {

    List<ProductionOrderPartialReleaseLineEntity> findByReleaseId(Long releaseId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ProductionOrderPartialReleaseLineEntity l WHERE l.releaseId = :releaseId")
    void deleteByReleaseId(@Param("releaseId") Long releaseId);
}
