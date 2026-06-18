package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosDepositSlipUpdateRequest {
    @NotBlank(message = "Debes indicar el número de boleta de depósito.")
    @Size(max = 40, message = "El número de boleta no puede exceder 40 caracteres.")
    private String depositSlipNumber;
}
