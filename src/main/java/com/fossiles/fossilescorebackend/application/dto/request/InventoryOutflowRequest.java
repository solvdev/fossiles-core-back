package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class InventoryOutflowRequest {

    @NotNull
    private Long fromLocationId;

    @NotNull
    private Long materialId;

    @NotNull
    @DecimalMin(value = "0.001", inclusive = true, message = "La cantidad debe ser mayor a 0")
    private BigDecimal quantity;

    private String reason;

    private String referenceType;

    private Long referenceId;

    private String referenceNumber;
}
