package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollIncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PayrollIncidentRepository extends JpaRepository<PayrollIncidentEntity, Long> {

    /** Las novedades activas de esas personas que tocan el período. */
    @Query("SELECT i FROM PayrollIncidentEntity i WHERE i.status = 'ACTIVA' AND i.employeeId IN :employeeIds "
            + "AND i.dateFrom <= :to AND i.dateTo >= :from")
    List<PayrollIncidentEntity> findActiveOverlapping(@Param("employeeIds") Collection<Long> employeeIds,
                                                     @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Las novedades activas de todos que tocan el rango, para la vista de novedades. */
    @Query("SELECT i FROM PayrollIncidentEntity i WHERE i.status = 'ACTIVA' AND i.dateFrom <= :to AND i.dateTo >= :from "
            + "ORDER BY i.dateFrom DESC, i.id DESC")
    List<PayrollIncidentEntity> findActiveBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Las activas de una persona que se cruzan con un rango, para no registrar dos veces lo mismo. */
    @Query("SELECT i FROM PayrollIncidentEntity i WHERE i.status = 'ACTIVA' AND i.employeeId = :employeeId "
            + "AND i.dateFrom <= :to AND i.dateTo >= :from")
    List<PayrollIncidentEntity> findActiveOfEmployeeBetween(@Param("employeeId") Long employeeId,
                                                           @Param("from") LocalDate from, @Param("to") LocalDate to);
}
