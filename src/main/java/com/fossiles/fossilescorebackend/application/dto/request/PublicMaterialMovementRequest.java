package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicMaterialMovementRequest {

    @NotBlank(message = "Movement type is required")
    private String movementType; // IN | OUT

    @DecimalMin(value = "0.001", message = "Quantity must be greater than zero")
    private BigDecimal quantity;

    @DecimalMin(value = "0.001", message = "Input quantity must be greater than zero")
    private BigDecimal inputQuantity;

    private Long inputUomId;

    @DecimalMin(value = "0.000001", message = "Conversion factor must be greater than zero")
    private BigDecimal conversionFactorToBase;

    @NotBlank(message = "Reason is required")
    private String reason;

    /** Ej. SHIPMENT_PACKING para idempotencia por envío. */
    private String referenceType;

    private Long referenceId;
}
