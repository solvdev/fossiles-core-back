package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Una fila de la cartera por documento (cargo). Invariante:
 * {@code balanceDue = chargedAmount - paymentsApplied - creditsApplied}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountPortfolioRowResponse {
    private Long customerId;
    private String customerName;
    private String legacyCode;
    private String nit;
    private String routeLocationCode;
    private String routeLocationLabel;

    /** DOCUMENT | OPENING_BALANCE | ORPHAN_CREDIT (abono aplicado a un cargo anulado). */
    private String rowType;
    private String orderKind;

    /** Cargo principal del documento (primero de {@link #chargeEntryIds}). */
    private Long chargeEntryId;
    /** Todos los cargos activos agrupados en esta fila (más de uno = cargo duplicado en el libro). */
    private List<Long> chargeEntryIds;
    private int chargeCount;
    /** true cuando el mismo documento tiene más de un cargo activo y se consolidó en una sola fila. */
    private boolean duplicateCharges;

    private String documentNumber;
    private String invoiceNumber;
    private String orderCode;
    private String shipmentNumber;
    private String vendorShipmentNumber;

    private LocalDate chargeDate;
    private LocalDate lastPaymentDate;

    private BigDecimal chargedAmount;
    /** Efectivo cobrado (monto neto de los pagos activos). */
    private BigDecimal paymentsApplied;
    /** Notas de crédito + devoluciones + descuentos aplicados al cobrar. */
    private BigDecimal creditsApplied;
    private BigDecimal balanceDue;

    private int paymentCount;
    private int creditCount;
    /** OPEN | PARTIAL | PAID */
    private String status;
}
