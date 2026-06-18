package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FelSignerService {

    private final FelEmissionProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    public String signXml(String unsignedXml, String internalCode) throws BusinessException {
        return signXml(unsignedXml, internalCode, false);
    }

    public String signXml(String unsignedXml, String internalCode, boolean annulment) throws BusinessException {
        validateSignConfig();
        String payloadBase64 = Base64.getEncoder().encodeToString(unsignedXml.getBytes(StandardCharsets.UTF_8));

        Map<String, String> body = new LinkedHashMap<>();
        body.put("llave", properties.getSignKey().trim());
        body.put("archivo", payloadBase64);
        body.put("codigo", internalCode);
        body.put("alias", properties.getSignAlias().trim());
        body.put("es_anulacion", annulment ? "S" : "N");

        try {
            String responseBody = restClient.post()
                    .uri(properties.getSignUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseSignedXml(responseBody);
        } catch (RestClientException ex) {
            throw new BusinessException("Error al firmar DTE en FEL: " + ex.getMessage());
        }
    }

    private String parseSignedXml(String raw) throws BusinessException {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Firma FEL: respuesta vacía.");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<dte:")) {
            return trimmed;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.isTextual()) {
                return decodeMaybeBase64(node.asText());
            }
            boolean ok = node.path("resultado").asBoolean(true);
            if (!ok) {
                throw new BusinessException(firstMessage(node, "Firma FEL rechazada."));
            }
            String archivo = text(node.get("archivo"));
            if (archivo.isBlank()) {
                archivo = text(node.get("xml"));
            }
            if (archivo.isBlank()) {
                throw new BusinessException("Firma FEL: no se recibió XML firmado.");
            }
            return decodeMaybeBase64(archivo);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Firma FEL: respuesta no interpretable.");
        }
    }

    private void validateSignConfig() throws BusinessException {
        if (isBlank(properties.getSignKey()) || isBlank(properties.getSignAlias())) {
            throw new BusinessException("Faltan credenciales de firma FEL (fel.emission.sign-key / sign-alias).");
        }
    }

    private static String decodeMaybeBase64(String value) throws BusinessException {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<dte:")) {
            return trimmed;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Firma FEL: XML firmado inválido.");
        }
    }

    private static String firstMessage(JsonNode node, String fallback) {
        String msg = text(node.get("descripcion"));
        if (msg.isBlank()) {
            msg = text(node.get("mensaje"));
        }
        return msg.isBlank() ? fallback : msg;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
