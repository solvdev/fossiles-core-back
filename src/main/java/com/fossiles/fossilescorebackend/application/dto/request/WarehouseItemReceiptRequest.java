package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Recepción por cantidad: cuántas piezas pendientes de cada línea de la orden se reciben ahora. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseItemReceiptRequest {
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productionOrderItemId;
        /** Talla de la línea; vacío si no tiene. */
        private String sizeKey;
        private int quantity;
    }
}
