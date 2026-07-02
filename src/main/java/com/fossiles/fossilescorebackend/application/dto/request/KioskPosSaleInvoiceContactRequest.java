package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosSaleInvoiceContactRequest {
    /** Correo(s) del receptor FEL; varios separados por punto y coma sin espacios. */
    private String email;
    private String phone;
}
