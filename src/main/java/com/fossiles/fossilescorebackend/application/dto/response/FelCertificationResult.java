package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FelCertificationResult {
    private String status;
    private String uuid;
    private String serie;
    private String numero;
    private String description;
    private String errorMessage;
    /** XML del DTE certificado (decodificado desde xml_certificado Base64 de INFILE). */
    private String certifiedXml;
}
