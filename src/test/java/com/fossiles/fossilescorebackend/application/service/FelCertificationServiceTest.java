package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.FelCertificationResult;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FelCertificationServiceTest {

    @Mock
    private FelEmissionProperties properties;

    private final FelCertificationService service = new FelCertificationService(properties, new ObjectMapper());

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseResponseDecodesCertifiedXml() throws Exception {
        String xml = "<dte:GTDocumento>certificado</dte:GTDocumento>";
        String encoded = Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        String raw = objectMapper.writeValueAsString(java.util.Map.of(
                "resultado", true,
                "uuid", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "serie", "ABC12345",
                "numero", "12345678",
                "descripcion", "OK",
                "xml_certificado", encoded
        ));

        FelCertificationResult result = invokeParse(raw);

        assertThat(result.getStatus()).isEqualTo("CERTIFIED");
        assertThat(result.getCertifiedXml()).isEqualTo(xml);
    }

    private FelCertificationResult invokeParse(String raw) throws Exception {
        var method = FelCertificationService.class.getDeclaredMethod("parseResponse", String.class);
        method.setAccessible(true);
        return (FelCertificationResult) method.invoke(service, raw);
    }
}
