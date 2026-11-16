package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineLedgerLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface OnlineLedgerLogRepository extends JpaRepository<OnlineLedgerLogEntity, Long> {

    /** Por periodo y tipo de evento: [periodId, eventType, cantidad, último momento]. */
    @Query("SELECT l.periodId, l.eventType, COUNT(l), MAX(l.createdAt) FROM OnlineLedgerLogEntity l "
            + "WHERE l.periodId IN :ids GROUP BY l.periodId, l.eventType")
    List<Object[]> countByPeriodAndType(@Param("ids") Collection<Long> ids);

    /** Personas distintas que cambiaron celdas, por periodo: [periodId, personas]. */
    @Query("SELECT l.periodId, COUNT(DISTINCT l.userName) FROM OnlineLedgerLogEntity l "
            + "WHERE l.periodId IN :ids AND l.eventType = 'EDIT' GROUP BY l.periodId")
    List<Object[]> editorsByPeriod(@Param("ids") Collection<Long> ids);

    /** Cambios de celdas por persona: [nombre, cambios, ventas distintas, último momento]. */
    @Query("SELECT l.userName, COUNT(l), COUNT(DISTINCT l.saleId), MAX(l.createdAt) FROM OnlineLedgerLogEntity l "
            + "WHERE l.periodId = :id AND l.eventType = 'EDIT' GROUP BY l.userName ORDER BY COUNT(l) DESC")
    List<Object[]> editsByUser(@Param("id") Long periodId);

    /** Cambios de celdas por campo: [campo, cambios]. */
    @Query("SELECT l.fieldLabel, COUNT(l) FROM OnlineLedgerLogEntity l "
            + "WHERE l.periodId = :id AND l.eventType = 'EDIT' GROUP BY l.fieldLabel ORDER BY COUNT(l) DESC")
    List<Object[]> editsByField(@Param("id") Long periodId);

    /** Ventas distintas con algún cambio de celda en el periodo. */
    @Query("SELECT COUNT(DISTINCT l.saleId) FROM OnlineLedgerLogEntity l WHERE l.periodId = :id AND l.eventType = 'EDIT'")
    long editedSales(@Param("id") Long periodId);
}
