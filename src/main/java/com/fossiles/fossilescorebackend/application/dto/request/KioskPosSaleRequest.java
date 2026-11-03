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
    /** Número de autorización de la transacción con tarjeta. */
    private String cardAuthNumber;
    /** Últimos 4 dígitos de la tarjeta. */
    private String cardLast4;
    /** Marca de tarjeta: VISA, MC o AMEX. */
    private String cardBrand;
    /**
     * Monto del voucher físico (puede diferir del total facturado).
     * No altera el total de la venta ni FEL.
     */
    private BigDecimal cardVoucherAmount;
    /** Monto cobrado en la segunda tarjeta (pago TARJETA dividido). */
    private BigDecimal card2Amount;
    private String card2AuthNumber;
    private String card2Last4;
    private String card2Brand;
    /** Monto del voucher físico de la segunda tarjeta. */
    private BigDecimal card2VoucherAmount;
    private String notes;
    private String comments;
    private Long promotionId;
    /** Descuento porcentual rápido (10, 15, etc.) cuando no hay promoción de catálogo. */
    private BigDecimal manualDiscountPercent;
    /** Crédito fijo por boleta de cambio (valor del producto devuelto). */
    private BigDecimal exchangeCreditAmount;
    /** Número de boleta de cambio para referencia en la venta POS. */
    private String exchangeSlipNumber;
    private LocalDate saleDate;
    /** Si el cliente es CF, indica si desea factura electrónica. */
    private Boolean requestInvoice;
    /** Cobrar precio de catálogo sin descuento POS ni promoción. */
    private Boolean chargeWithoutDiscount;
    /** Número de hoja de envío (Entrecueros). */
    private String shippingSheetNumber;

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
        /** Talla para cinchos (sizes_data en inventario del kiosko). */
        private String size;
        /** Herraje del stock kiosko: NUEVO o VIEJO. */
        private String hardwareCondition;

        @NotNull(message = "La cantidad es obligatoria.")
        @Positive(message = "La cantidad debe ser mayor a cero.")
        private BigDecimal quantity;

        /**
         * Precio unitario editado. Null o no positivo = precio de catálogo (o lista Entrecueros).
         * Si es mayor a cero, solo Miraflores (A15), un kiosko cuyo nombre contiene MIRAFLORES,
         * o administración pueden usarlo. También lo usa la venta de cambio.
         */
        private BigDecimal unitPrice;

        /**
         * Cómo aplicar {@code unitPrice} cuando el usuario puede editar el precio.
         * Null o true: la línea queda final, sin descuento POS ni promoción.
         * False: el precio editado es la base y el descuento POS/promo se calcula encima.
         * En Entrecueros la línea editada queda final de todos modos.
         */
        private Boolean priceFinal;

        /**
         * Solicitud de complemento extra AUTORIZADA que respalda esta línea. El servidor valida estado,
         * kiosko, producto y cantidad, y calcula el precio (catálogo + extra por pieza).
         */
        private Long extraRequestId;
    }
}
