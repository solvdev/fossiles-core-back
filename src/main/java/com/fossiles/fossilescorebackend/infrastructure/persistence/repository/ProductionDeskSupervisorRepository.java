package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionDeskSupervisorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionDeskSupervisorRepository extends JpaRepository<ProductionDeskSupervisorEntity, Long> {

    List<ProductionDeskSupervisorEntity> findByEffectiveDateOrderByDeskAsc(LocalDate effectiveDate);

    Optional<ProductionDeskSupervisorEntity> findByDeskAndEffectiveDate(Integer desk, LocalDate effectiveDate);

    /** Asignación vigente para la mesa en la fecha (último registro con effective_date &lt;= asOfDate). */
    Optional<ProductionDeskSupervisorEntity> findTopByDeskAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
            Integer desk,
            LocalDate asOfDate);

    void deleteByEffectiveDate(LocalDate effectiveDate);
}
