package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskMainSheetCertificationRequest {

    /** Persona que revisa y certifica la hoja principal. */
    @NotBlank(message = "Debes indicar quién revisa y certifica.")
    private String certifiedBy;

    /** Segunda revisión (misma lista de supervisores). */
    @NotBlank(message = "Debes indicar quién revisa.")
    private String reviewedBy;

    @NotNull(message = "Debes indicar la fecha inicial del inventario digital.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate inventoryFrom;

    @NotNull(message = "Debes indicar la fecha final del inventario digital.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate inventoryTo;

    @NotNull(message = "Debes indicar la fecha inicial de ventas.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate salesFrom;

    @NotNull(message = "Debes indicar la fecha final de ventas.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate salesTo;
}
