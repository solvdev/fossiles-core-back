package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/** Partes de cargos descontadas en líneas de planilla y lo que reservan los borradores. */
public interface EmployeeChargeApplicationRepository extends JpaRepository<EmployeeChargeApplicationEntity, Long> {
    List<EmployeeChargeApplicationEntity> findByRunLineIdIn(Collection<Long> runLineIds);

    List<EmployeeChargeApplicationEntity> findByChargeId(Long chargeId);

    @Modifying
    @Query("DELETE FROM EmployeeChargeApplicationEntity a WHERE a.runLineId IN :runLineIds")
    void deleteByRunLineIdIn(@Param("runLineIds") Collection<Long> runLineIds);

    /** Quita lo que los borradores tenían reservado de un cargo que se anula. */
    @Modifying
    @Query("DELETE FROM EmployeeChargeApplicationEntity a WHERE a.chargeId = :chargeId AND a.runLineId IN "
            + "(SELECT l.id FROM PayrollRunLineEntity l, PayrollRunEntity r WHERE l.runId = r.id AND r.status = 'BORRADOR')")
    void deleteDraftReservations(@Param("chargeId") Long chargeId);

    /** Lo reservado por otras planillas en borrador: aún no rebaja el saldo, pero no puede descontarse dos veces. */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM EmployeeChargeApplicationEntity a, PayrollRunLineEntity l, PayrollRunEntity r "
            + "WHERE a.runLineId = l.id AND l.runId = r.id AND a.chargeId = :chargeId AND r.status = 'BORRADOR' AND r.id <> :runId")
    BigDecimal reservedByOtherDrafts(@Param("chargeId") Long chargeId, @Param("runId") Long runId);
}
