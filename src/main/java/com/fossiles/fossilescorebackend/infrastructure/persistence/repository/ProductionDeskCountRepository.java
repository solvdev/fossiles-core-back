package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionDeskCountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ProductionDeskCountRepository extends JpaRepository<ProductionDeskCountEntity, Long> {
    Optional<ProductionDeskCountEntity> findTopByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(LocalDate effectiveDate);
    Optional<ProductionDeskCountEntity> findByEffectiveDate(LocalDate effectiveDate);
    void deleteByEffectiveDate(LocalDate effectiveDate);

    /** Mayor cantidad de mesas programada con fecha efectiva posterior a {@code date} (null si no hay). */
    @Query("SELECT MAX(c.numDesks) FROM ProductionDeskCountEntity c WHERE c.effectiveDate > :date")
    Integer findMaxNumDesksAfter(@Param("date") LocalDate date);
}
