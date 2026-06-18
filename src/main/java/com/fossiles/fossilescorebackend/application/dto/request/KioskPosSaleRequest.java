package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosSaleRequest {
    private Long kioskLocationId;
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private String paymentMethod;
    private BigDecimal amountReceived;
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
    private String notes;
    private String comments;
    private Long promotionId;
    private LocalDate saleDate;
    /** Si el cliente es CF, indica si desea factura electrónica. */
    private Boolean requestInvoice;

    @NotEmpty(message = "Debes agregar al menos un producto.")
    @Valid
    private List<ItemRequest> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemRequest {
        @NotNull(message = "El producto es obligatorio.")
        private Long productId;
        private Long colorId;

        @NotNull(message = "La cantidad es obligatoria.")
        @Positive(message = "La cantidad debe ser mayor a cero.")
        private BigDecimal quantity;
    }
}
