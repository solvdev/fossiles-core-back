package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionTierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KioskPromotionTierRepository extends JpaRepository<KioskPromotionTierEntity, Long> {
    List<KioskPromotionTierEntity> findByPromotionIdOrderByAudienceCategoryAsc(Long promotionId);

    void deleteByPromotionId(Long promotionId);
}
