package com.fossiles.fossilescorebackend.application.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Página de la consulta «Envíos» (todos los envíos del sistema por procedencia). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentSearchResponse {
    private List<Item> items;
    /** Envíos que cumplen todos los filtros (no solo los de esta página). */
    private long total;
    private BigDecimal totalUnits;
    /** Página (base 0) y tamaño. */
    private int page;
    private int size;
    /** Rango realmente usado (con el tope de 90 días si no se indicó). */
    private String dateField;
    private LocalDate from;
    private LocalDate to;
    private boolean defaultRangeApplied;
    /** El rango tenía más envíos que el máximo que se revisa: el total puede estar incompleto. */
    private boolean truncated;
    private int maxScanned;
    /** Cuántos envíos hay por procedencia con el resto de filtros (para los conteos de los chips). */
    private Map<String, Long> countsByProvenance;
    /** Cuántos envíos hay por estado con el resto de filtros. */
    private Map<String, Long> countsByStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** Mismos campos que el envío en el resto de la API (exportaciones e impresión los reutilizan). */
        @JsonUnwrapped
        private ProductShipmentResponse shipment;
        /** DIRECTO, DISTRIBUCION, REPOSICION, OP, PARCIAL, ENVP, ENVI, PENDIENTE, HISTORICO u OTRO. */
        private String provenance;
        /** Tipo de OP según su código: OPK, OPCK, OPC, OPV, OPL, OPI (null sin OP). */
        private String productionOrderType;
        /** De dónde salió según el kardex: DEVOLUCIONES, BODEGA_PT, MIXTO; null si no movió bodega. */
        private String dispatchOrigin;
        private LocalDate documentDate;
        private BigDecimal totalUnits;
        private BigDecimal totalReceived;
        private Boolean receiptDifference;
        private String sentByName;
        private String receivedByName;
        private String locationDepartamento;
    }
}
