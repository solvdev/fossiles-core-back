package com.fossiles.fossilescorebackend.application.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Piezas por línea de OP cuyo cuero ya salió a mesa. Misma regla para el
 * Dashboard de Producción y el estimado de cuero por orden.
 * <p>
 * Una fila cuenta si el cuero está marcado entregado en la tarea o en la
 * línea, o si la tarea no está {@code PENDING}. Las canceladas ya vienen
 * excluidas de {@code findLeatherProgressRowsByItemIds}. El tope por
 * cantidad de línea lo aplica el llamador.
 */
final class ProductionLeatherDeliveredUnits {

    private ProductionLeatherDeliveredUnits() {}

    static Map<Long, Integer> fromProgressRows(List<Object[]> rows) {
        Map<Long, Integer> out = new HashMap<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        for (Object[] row : rows) {
            Long itemId = (Long) row[0];
            Integer qty = (Integer) row[1];
            String status = (String) row[2];
            if ("CANCELLED".equals(status)) {
                continue;
            }
            boolean leatherOut = Boolean.TRUE.equals(row[3]) || Boolean.TRUE.equals(row[4])
                    || (status != null && !"PENDING".equals(status));
            if (itemId != null && qty != null && qty > 0 && leatherOut) {
                out.merge(itemId, qty, Integer::sum);
            }
        }
        return out;
    }
}
