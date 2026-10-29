package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollRunEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Planillas por grupo y fecha de pago, con bloqueo para los cambios de estado. */
public interface PayrollRunRepository extends JpaRepository<PayrollRunEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PayrollRunEntity r WHERE r.id = :id")
    Optional<PayrollRunEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT r FROM PayrollRunEntity r WHERE r.payGroup = :group AND r.scheduledDate = :date AND r.status <> 'ANULADA'")
    Optional<PayrollRunEntity> findActive(@Param("group") String group, @Param("date") LocalDate date);

    List<PayrollRunEntity> findAllByOrderByScheduledDateDescIdDesc();

    /** Incluye las anuladas: el borrador automático no debe volver a crear una planilla que alguien anuló. */
    boolean existsByPayGroupAndScheduledDate(String payGroup, LocalDate scheduledDate);

    /** Grupos que ya aprobaron o pagaron una planilla que cubre parte del período. */
    @Query("SELECT DISTINCT r.payGroup FROM PayrollRunEntity r WHERE r.id <> :runId AND r.status IN ('APROBADA', 'PAGADA') "
            + "AND r.periodStart <= :end AND r.periodEnd >= :start")
    List<String> findGroupsClosedForPeriod(@Param("runId") Long runId, @Param("start") LocalDate start,
                                           @Param("end") LocalDate end);

    /** La planilla anterior del mismo grupo, para comparar cuentas y formas de pago. */
    Optional<PayrollRunEntity> findFirstByPayGroupAndScheduledDateBeforeAndStatusNotOrderByScheduledDateDesc(
            String payGroup, LocalDate scheduledDate, String status);
}
