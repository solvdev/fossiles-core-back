package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Carrito del POS a dejar en espera. El servidor solo lo guarda; el front lo arma y lo restaura. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosHeldCartRequest {
    private Long kioskLocationId;

    @Size(max = 160, message = "La etiqueta admite máximo 160 caracteres.")
    private String label;

    /** JSON del carrito (líneas, promoción elegida, notas). */
    @NotBlank(message = "El carrito está vacío.")
    @Size(max = 200000, message = "El carrito es demasiado grande.")
    private String cartJson;
}
