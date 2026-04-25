package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface KioskPromotionRepository extends JpaRepository<KioskPromotionEntity, Long> {
    Optional<KioskPromotionEntity> findByIdAndActiveTrue(Long id);
    List<KioskPromotionEntity> findByActiveTrueOrderByNameAsc();
    List<KioskPromotionEntity> findByActiveTrueAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByNameAsc(
            LocalDate dateA,
            LocalDate dateB
    );
}
