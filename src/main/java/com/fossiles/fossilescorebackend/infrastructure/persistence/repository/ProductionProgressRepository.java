package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Consultas de solo lectura para el avance por orden del Dashboard de Producción.
 * Aparte de {@link TaskRepository} para no mezclarlas con las del tablero.
 */
public interface ProductionProgressRepository extends Repository<TaskEntity, Long> {

    /**
     * Tareas no canceladas que trabajan alguna línea de estas órdenes: por sus task_item
     * (incluye extras OPL que vienen de otra OP) o, en tareas viejas sin task_item, por la
     * línea guardada en la propia tarea.
     */
    @Query("""
            SELECT t FROM TaskEntity t
            WHERE t.status <> 'CANCELLED'
              AND (
                EXISTS (
                  SELECT 1 FROM TaskItemEntity ti, ProductionOrderItemEntity poi
                  WHERE ti.taskId = t.id
                    AND poi.id = ti.productionOrderItemId
                    AND poi.productionOrderId IN :orderIds)
                OR (
                  NOT EXISTS (SELECT 1 FROM TaskItemEntity ti2 WHERE ti2.taskId = t.id)
                  AND EXISTS (
                    SELECT 1 FROM ProductionOrderItemEntity poi2
                    WHERE poi2.id = t.productionOrderItemId
                      AND poi2.productionOrderId IN :orderIds))
              )
            """)
    List<TaskEntity> findActiveTasksForOrders(@Param("orderIds") Collection<Long> orderIds);
}
