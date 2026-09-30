package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PUT /api/kiosk-financials/config/bulk.
 * <p>
 * Semantica de presencia: solo se tocan las claves presentes en el JSON.
 * Para meta y tasas se distingue clave ausente (campo {@code null} = no tocar) de clave con {@code null}
 * explicito (Jackson la deserializa como {@code Optional.empty()} = limpiar el valor).
 * En {@code costs}, un valor {@code null} borra la fila de esa categoria.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsConfigBulkRequest {

    private Integer year;
    private List<Change> changes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {
        private Long siteId;
        private Integer month;
        private Optional<BigDecimal> goal;
        private Optional<BigDecimal> productCostPct;
        private Optional<BigDecimal> salesCommissionPct;
        private Optional<BigDecimal> cardCommissionPct;
        private Optional<BigDecimal> taxPct;
        private Map<String, BigDecimal> costs;
    }
}
