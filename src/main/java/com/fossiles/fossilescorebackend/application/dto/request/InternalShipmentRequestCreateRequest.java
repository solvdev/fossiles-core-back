package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestCreateRequest {

    @NotBlank(message = "El tipo de solicitud es obligatorio")
    private String requestType;

    @NotBlank(message = "El nombre del colaborador es obligatorio")
    private String recipientName;

    /** Empleado de planilla vinculado a la solicitud (opcional para DEFECTOS). */
    private Long employeeId;

    private String recipientPhone;

    private String recipientTaxId;

    private String notes;

    private String documentDate;

    /** DEFECTOS: porcentaje del precio catálogo. PLANILLA: omitir (50% fijo). */
    private java.math.BigDecimal discountPercent;

    /** DEFECTOS: precio unitario fijo Q. */
    private java.math.BigDecimal discountAmount;

    @NotEmpty(message = "Debe incluir al menos un producto")
    @Valid
    private List<ProductShipmentRequest.ProductShipmentDetailRequest> products;
}
