package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollRunLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** Líneas de planilla: quién ya cobró un período y qué cheques se usaron. */
public interface PayrollRunLineRepository extends JpaRepository<PayrollRunLineEntity, Long> {
    List<PayrollRunLineEntity> findByRunIdOrderByEmployeeNameAscIdAsc(Long runId);

    boolean existsByEmployeeId(Long employeeId);

    /** Quien ya tiene línea en otra planilla no anulada cuyo período se traslapa: ya cobró esa quincena. */
    @Query("SELECT DISTINCT l.employeeId FROM PayrollRunLineEntity l, PayrollRunEntity r WHERE l.runId = r.id "
            + "AND r.id <> :runId AND r.status <> 'ANULADA' AND r.periodStart <= :end AND r.periodEnd >= :start")
    List<Long> findEmployeesPaidInOtherRuns(@Param("runId") Long runId, @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    /** Cheques asignados en otras planillas que no están anuladas: número y planilla. */
    @Query("SELECT l.chequeNumber, r.id FROM PayrollRunLineEntity l, PayrollRunEntity r "
            + "WHERE l.runId = r.id AND r.status <> 'ANULADA' AND r.id <> :runId AND l.chequeNumber IS NOT NULL")
    List<Object[]> findChequesOfOtherActiveRuns(@Param("runId") Long runId);

    /** Cheques de cualquier otra planilla, anuladas incluidas: la chequera física ya pudo usarlos. */
    @Query("SELECT l.chequeNumber FROM PayrollRunLineEntity l WHERE l.runId <> :runId AND l.chequeNumber IS NOT NULL")
    List<String> findChequeNumbersOfAllOtherRuns(@Param("runId") Long runId);
}
