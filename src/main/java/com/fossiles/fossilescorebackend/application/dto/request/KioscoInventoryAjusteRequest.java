package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoInventoryAjusteRequest {

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;

    private Long colorId;

    @NotNull(message = "La cantidad real es obligatoria.")
    @Min(value = 0, message = "La cantidad real no puede ser negativa.")
    private Integer realQuantity;

    @NotBlank(message = "El motivo es obligatorio.")
    private String reason;

    private Long userId;
}
