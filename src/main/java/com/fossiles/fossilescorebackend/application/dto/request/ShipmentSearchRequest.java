package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** Filtros de GET /shipments/search. Todos opcionales y combinables. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentSearchRequest {
    /** created (por defecto), sent, received o doc (fecha del documento). */
    private String dateField;
    private LocalDate from;
    private LocalDate to;
    private List<Long> kioskIds;
    private List<String> provenances;
    private List<String> statuses;
    private String shipmentNumber;
    private String distributionNumber;
    private String productionOrderCode;
    private String productionOrderType;
    /** Producto, código, color o talla; varias palabras deben coincidir todas en la misma línea. */
    private String product;
    /** DEVOLUCIONES, BODEGA_PT o MIXTO. */
    private String dispatchOrigin;
    private Boolean receiptDifference;
    private Boolean pendingProduction;
    /** Nombre de quien creó, envió o recibió. */
    private String person;
    private String notes;
    /** date-desc (por defecto), date-asc, kiosk, units, number. */
    private String order;
    private Integer page;
    private Integer size;
}
