package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestResponse {
    private Long id;
    private String status;
    private String requestType;
    private String recipientName;
    private Long employeeId;
    private String recipientPhone;
    private String recipientTaxId;
    private String notes;
    private String documentDate;
    private String slipNumber;
    private java.math.BigDecimal discountPercent;
    private java.math.BigDecimal discountAmount;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String rejectionReason;
    private Long productShipmentId;
    private String shipmentNumber;
    private Long productionOrderId;
    private String productionOrderCode;
    private String productionOrderStatus;
    /** True si el ENVI ya existe y aún no tiene OPI generada desde esta solicitud. */
    private Boolean canGenerateOpi;
    private Long opiAuthorizedBy;
    private LocalDateTime opiAuthorizedAt;
    /**
     * Estado de stock (Devoluciones + Bodega PT) para solicitudes PENDIENTES de envío:
     * DISPONIBLE (alcanza, se puede autorizar el envío), SIN_STOCK (falta, se autoriza la producción),
     * EN_PRODUCCION (falta, pero la OPI ya está autorizada: esperar el ingreso a Bodega PT).
     * Null si no aplica (solicitud ya resuelta, tipo OPI o no se pudo calcular).
     */
    private String stockStatus;
    /** Unidades totales faltantes (suma de líneas con faltante). */
    private BigDecimal stockShortageTotal;
    /** Acción principal disponible para Contabilidad: AUTORIZAR_ENVIO, AUTORIZAR_PRODUCCION o ESPERAR_PRODUCCION. */
    private String nextAction;
    private List<LineResponse> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineResponse {
        private Long id;
        private Integer lineOrder;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private BigDecimal quantity;
        private BigDecimal catalogPrice;
        /** Stock disponible hoy (Devoluciones + Bodega PT); solo en solicitudes pendientes. */
        private BigDecimal availableQuantity;
        /** Unidades que faltan para cubrir esta línea; 0 si alcanza. */
        private BigDecimal shortageQuantity;
    }
}
