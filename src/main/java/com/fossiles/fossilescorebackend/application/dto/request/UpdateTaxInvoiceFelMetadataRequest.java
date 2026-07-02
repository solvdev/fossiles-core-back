package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class UpdateTaxInvoiceFelMetadataRequest {

    @NotBlank
    @Size(max = 64)
    private String felUuid;

    @NotBlank
    @Size(max = 32)
    private String felSerie;

    @NotBlank
    @Size(max = 32)
    private String felNumero;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate felCertifiedDate;

    @Size(max = 500)
    private String correctionNotes;
}
