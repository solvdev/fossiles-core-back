package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BomItemRepository extends JpaRepository<BomItemEntity, Long> {
    List<BomItemEntity> findByBomId(Long bomId);
}

