package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class MaterialRequestRequest {
    @Size(max = 50, message = "Origin must not exceed 50 characters")
    private String origin; // ORDEN_PRODUCCION, TAREA, REPOSICION, etc. (opcional - puede ser null)

    private Long originReferenceId;

    @Valid
    @NotEmpty(message = "Items are required")
    private List<MaterialRequestItemRequest> items;

    @Size(max = 500, message = "Observations must not exceed 500 characters")
    private String observations;
}

