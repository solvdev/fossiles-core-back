package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Cargos a colaboradores: los que la planilla puede descontar y sus bloqueos por id. */
public interface EmployeeChargeRepository extends JpaRepository<EmployeeChargeEntity, Long> {
    Optional<EmployeeChargeEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM EmployeeChargeEntity c WHERE c.id = :id")
    Optional<EmployeeChargeEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * Cargos que la planilla puede descontar. Se bloquean por id, el mismo orden que usan aprobar y anular, para que
     * dos operaciones simultáneas no se bloqueen entre sí; el orden de descuento lo aplica quien llama.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM EmployeeChargeEntity c WHERE c.employeeId IN :employeeIds AND c.settlementMethod = 'DESCUENTO' "
            + "AND c.status IN ('PENDIENTE', 'PARCIAL') AND c.balance > 0 AND c.chargedOn <= :until ORDER BY c.id")
    List<EmployeeChargeEntity> findDeductibleForUpdate(@Param("employeeIds") Collection<Long> employeeIds,
                                                      @Param("until") LocalDate until);

    /** Bloquea varios cargos por id, siempre en el mismo orden. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM EmployeeChargeEntity c WHERE c.id IN :ids ORDER BY c.id")
    List<EmployeeChargeEntity> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    boolean existsByEmployeeId(Long employeeId);

    /** Igual que {@link #findDeductibleForUpdate} sin bloqueo, para consultas de solo lectura. */
    @Query("SELECT c FROM EmployeeChargeEntity c WHERE c.employeeId IN :employeeIds AND c.settlementMethod = 'DESCUENTO' "
            + "AND c.status IN ('PENDIENTE', 'PARCIAL') AND c.balance > 0 AND c.chargedOn <= :until ORDER BY c.chargedOn, c.id")
    List<EmployeeChargeEntity> findDeductible(@Param("employeeIds") Collection<Long> employeeIds,
                                              @Param("until") LocalDate until);

    List<EmployeeChargeEntity> findAllByOrderByChargedOnDescIdDesc();
}
