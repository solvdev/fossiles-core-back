package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Cuerpo opcional para POST /api/tasks/plan-window.
 * Persiste schedulingPriority en cada OP y ordena la planificación entre OPs por ese campo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanWindowRequest {
    /** IDs de orden de producción (string como claves JSON) → prioridad menor = se planifica antes. */
    private Map<String, Integer> schedulingPriorities;
}
