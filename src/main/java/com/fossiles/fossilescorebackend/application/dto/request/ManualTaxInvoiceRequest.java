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
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualTaxInvoiceRequest {

    @NotBlank(message = "El NIT o CF es obligatorio.")
    private String customerTaxId;

    /** FACT (factura) o FCAM (factura cambiaria). Por defecto FACT. */
    private String documentType;

    private String customerName;
    private String address;
    private String phone;
    private String email;
    private String notes;

    @NotEmpty(message = "Debe incluir al menos una línea.")
    @Valid
    private List<LineRequest> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineRequest {
        @NotBlank(message = "La descripción es obligatoria.")
        private String description;

        @NotNull(message = "La cantidad es obligatoria.")
        @Positive(message = "La cantidad debe ser mayor a cero.")
        private BigDecimal quantity;

        @NotNull(message = "El precio unitario es obligatorio.")
        @Positive(message = "El precio unitario debe ser mayor a cero.")
        private BigDecimal unitPrice;
    }
}
