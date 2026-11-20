package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchStockPreviewResponse {
    private BigDecimal availableTotal;
    private List<DispatchStockBreakdownRow> breakdown;
    /** Bodega de la que sale sin autorización (p. ej. "Devoluciones" para un envío directo). */
    private String primaryWarehouseLabel;
    /** Total contando también la cubeta de respaldo (solo con autorización); null si no aplica. */
    private BigDecimal fallbackAvailableTotal;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DispatchStockBreakdownRow {
        private Long locationId;
        private String locationCode;
        private String locationName;
        private BigDecimal quantity;
    }
}
