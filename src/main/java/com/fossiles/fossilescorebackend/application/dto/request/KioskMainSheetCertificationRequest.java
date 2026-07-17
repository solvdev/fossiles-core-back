package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
