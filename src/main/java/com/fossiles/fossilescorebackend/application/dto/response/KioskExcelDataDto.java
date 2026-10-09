package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Datos parseados de un Excel (mismo contenido que viaja de vuelta en el commit).
 * Las claves de los mapas son el nombre de la columna del Excel (recortado, tal cual viene).
 * Celda vacía = {@code null} (no es lo mismo que 0).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExcelDataDto {

    private List<Day> days;
    private Map<String, BigDecimal> goals;
    private Map<String, Rates> rates;
    /** columna -> (código de categoría -> monto | null). */
    private Map<String, Map<String, BigDecimal>> costs;
    /** Celdas de ventas que requieren resolución (texto, negativos). El valor en {@code days} es null (texto) o el negativo. */
    private List<BlockedCell> blockedCells;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        private LocalDate date;
        private Map<String, BigDecimal> values;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rates {
        private BigDecimal productCostPct;
        private BigDecimal salesCommissionPct;
        private BigDecimal cardCommissionPct;
        private BigDecimal taxPct;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlockedCell {
        /** Id del issue BLOCKING asociado (clave en {@code resolutions}). */
        private String issueId;
        private String code;
        private String excelName;
        private LocalDate date;
        private String cell;
        private String rawValue;
    }
}
