package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Avance de recepción en la reserva de Luis Felipe de una OPV: piezas recibidas de las que pide la orden. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseLfReceiptResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private String status;
    private LocalDate deliveryDate;
    private int totalUnits;
    private int receivedUnits;
    /** Se recibieron todas las piezas de la orden. */
    private boolean complete;
}
