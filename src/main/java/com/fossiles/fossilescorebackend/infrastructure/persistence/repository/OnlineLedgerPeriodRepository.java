package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineLedgerPeriodEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface OnlineLedgerPeriodRepository extends JpaRepository<OnlineLedgerPeriodEntity, Long> {

    /** Periodos que se cruzan con [from, to]. */
    @Query("SELECT p FROM OnlineLedgerPeriodEntity p WHERE p.dateFrom <= :to AND p.dateTo >= :from ORDER BY p.dateFrom")
    List<OnlineLedgerPeriodEntity> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);

    List<OnlineLedgerPeriodEntity> findTop60ByOrderByDateFromDesc();
}
