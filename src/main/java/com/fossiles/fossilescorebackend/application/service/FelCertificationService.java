package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.FelCertificationResult;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.config.FelCredentials;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FelCertificationService {

    private final FelEmissionProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    public FelCertificationResult certifySignedXml(String signedXml, String transactionId, FelCredentials credentials)
            throws BusinessException {
        return postCertification(signedXml, transactionId, properties.getCertifyUrl(), credentials);
    }

    public FelCertificationResult certifyAnnulmentSignedXml(
            String signedXml, String transactionId, FelCredentials credentials) throws BusinessException {
        String annulUrl = properties.getAnnulUrl();
        if (isBlank(annulUrl)) {
            throw new BusinessException("Falta URL de anulación FEL (fel.emission.annul-url).");
        }
        return postCertification(signedXml, transactionId, annulUrl.trim(), credentials);
    }

    private FelCertificationResult postCertification(
            String signedXml, String transactionId, String url, FelCredentials credentials) throws BusinessException {
        validateCertConfig(credentials);

        String xmlBase64 = Base64.getEncoder().encodeToString(signedXml.getBytes(StandardCharsets.UTF_8));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("nit_emisor", credentials.nitEmisor().trim());
        body.put("correo_copia", safe(credentials.correoEmisor()));
        body.put("xml_dte", xmlBase64);

        try {
            String responseBody = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Usuario", credentials.certUsuario().trim())
                    .header("Llave", credentials.certLlave().trim())
                    .header("Identificador", transactionId)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseResponse(responseBody);
        } catch (RestClientException ex) {
            throw new BusinessException("Error al certificar DTE en FEL: " + ex.getMessage());
        }
    }

    private FelCertificationResult parseResponse(String raw) throws BusinessException {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Certificación FEL: respuesta vacía.");
        }
        try {
            JsonNode node = objectMapper.readTree(raw.trim());
            boolean ok = node.path("resultado").asBoolean(false);
            String description = text(node.get("descripcion"));
            if (ok) {
                return FelCertificationResult.builder()
                        .status("CERTIFIED")
                        .uuid(text(node.get("uuid")))
                        .serie(text(node.get("serie")))
                        .numero(text(node.get("numero")))
                        .description(description)
                        .certifiedXml(decodeCertifiedXml(text(node.get("xml_certificado"))))
                        .build();
            }
            String error = buildErrorMessage(node, description);
            return FelCertificationResult.builder()
                    .status("FAILED")
                    .description(description)
                    .errorMessage(error)
                    .build();
        } catch (Exception ex) {
            throw new BusinessException("Certificación FEL: respuesta no interpretable.");
        }
    }

    private static String buildErrorMessage(JsonNode node, String description) {
        List<String> parts = new ArrayList<>();
        if (!description.isBlank()) {
            parts.add(description);
        }
        JsonNode errors = node.get("descripcion_errores");
        if (errors != null && errors.isArray()) {
            errors.forEach(err -> {
                String msg = text(err.get("mensaje_error"));
                if (msg.isBlank()) {
                    msg = err.asText("");
                }
                if (!msg.isBlank()) {
                    parts.add(msg);
                }
            });
        }
        if (parts.isEmpty()) {
            return "El certificador rechazó el DTE.";
        }
        return String.join(" | ", parts);
    }

    private void validateCertConfig(FelCredentials credentials) throws BusinessException {
        if (isBlank(credentials.certUsuario()) || isBlank(credentials.certLlave())) {
            throw new BusinessException("Faltan credenciales de certificación FEL (fel.emission.cert-usuario / cert-llave).");
        }
        if (isBlank(credentials.nitEmisor())) {
            throw new BusinessException("Falta NIT emisor FEL (fel.emission.nit-emisor).");
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String decodeCertifiedXml(String base64Xml) {
        if (base64Xml == null || base64Xml.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(base64Xml.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            log.warn("No se pudo decodificar xml_certificado FEL: {}", ex.getMessage());
            return null;
        }
    }
}
