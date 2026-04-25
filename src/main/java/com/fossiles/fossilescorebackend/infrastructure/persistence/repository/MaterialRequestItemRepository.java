package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialRequestItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaterialRequestItemRepository extends JpaRepository<MaterialRequestItemEntity, Long> {
    List<MaterialRequestItemEntity> findByMaterialRequestId(Long materialRequestId);
    void deleteByMaterialRequestId(Long materialRequestId);
}

