package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Solicitud de complemento extra: monto adicional POR PIEZA para N piezas de un producto del carrito. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosExtraRequestCreateRequest {
    private Long kioskLocationId;

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;
    private Long colorId;
    private String size;
    /** Dimensión de stock de la línea (NUEVO/VIEJO, marca, NINO/DAMA). */
    private String hardwareCondition;

    @NotNull(message = "La cantidad es obligatoria.")
    @Positive(message = "La cantidad debe ser mayor a cero.")
    private BigDecimal quantity;

    @NotBlank(message = "Describe el complemento.")
    @Size(max = 300, message = "La descripción del complemento admite máximo 300 caracteres.")
    private String description;

    @NotNull(message = "El monto extra es obligatorio.")
    @Positive(message = "El monto extra debe ser mayor a cero.")
    private BigDecimal extraAmount;

    /** Precio unitario que ve el cajero (informativo para quien autoriza). */
    private BigDecimal catalogUnitPrice;
}
