package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Restaura una venta POS eliminada por error, reutilizando el mismo sale_number.
 * No mueve inventario ni exige caja abierta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosSaleRestoreRequest {
    @NotBlank(message = "El número de venta es obligatorio.")
    private String saleNumber;

    private Long kioskLocationId;
    private LocalDate saleDate;
    private LocalDateTime soldAt;

    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private String paymentMethod;
    private BigDecimal amountReceived;
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
    private String cardAuthNumber;
    private String cardLast4;
    private String notes;
    private String comments;
    private String depositSlipNumber;

    /** Si se envían, se usan en lugar de los calculados desde catálogo. */
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;

    /** Datos FEL ya emitidos fuera del sistema (opcional). */
    private String felStatus;
    private String felUuid;
    private String felSerie;
    private String felNumero;
    private String felError;
    private LocalDateTime felCertifiedAt;

    /** Por defecto crea borrador tax_invoice (sin certificar FEL). */
    private Boolean createTaxInvoiceDraft;

    @NotEmpty(message = "Debes agregar al menos un producto.")
    @Valid
    private List<RestoreItemRequest> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RestoreItemRequest {
        @NotNull(message = "El producto es obligatorio.")
        private Long productId;
        private Long colorId;
        private String size;

        @NotNull(message = "La cantidad es obligatoria.")
        @Positive(message = "La cantidad debe ser mayor a cero.")
        private BigDecimal quantity;

        /** Precio unitario histórico (opcional). */
        private BigDecimal unitPrice;
        /** Total de línea histórico (opcional). */
        private BigDecimal lineTotal;
    }
}
