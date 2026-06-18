package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.TaxpayerLookupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.config.FelReceptorProperties;
import com.fossiles.fossilescorebackend.infrastructure.util.FelTaxpayerNameFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FelReceptorLookupService {

    private final FelReceptorProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    public TaxpayerLookupResponse lookup(String rawTaxId) throws BusinessException {
        String taxId = normalizeTaxId(rawTaxId);
        if (taxId == null || "CF".equals(taxId)) {
            return TaxpayerLookupResponse.builder()
                    .taxId("CF")
                    .customerName("CONSUMIDOR FINAL")
                    .build();
        }
        if (!properties.isEnabled()) {
            throw new BusinessException("La consulta de NIT no está habilitada en el servidor.");
        }
        if (isBlank(properties.getEmisorCodigo()) || isBlank(properties.getEmisorClave())) {
            throw new BusinessException("Faltan credenciales FEL (fel.receptor.emisor-codigo / emisor-clave).");
        }

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("emisor_codigo", properties.getEmisorCodigo().trim());
        payload.put("emisor_clave", properties.getEmisorClave().trim());
        payload.put("nit_consulta", taxId);

        try {
            String responseBody = restClient.post()
                    .uri(properties.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseResponse(responseBody, taxId);
        } catch (RestClientException ex) {
            throw new BusinessException("No se pudo consultar el NIT en FEL: " + ex.getMessage());
        }
    }

    private TaxpayerLookupResponse parseResponse(String raw, String requestedTaxId) throws BusinessException {
        if (isBlank(raw)) {
            throw new BusinessException("Consulta NIT: respuesta vacía del servicio.");
        }
        String trimmed = raw.trim();
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.isTextual()) {
                return parseCommaSeparated(node.asText(), requestedTaxId);
            }
            String message = text(node.get("mensaje"));
            if (!isBlank(message)) {
                throw new BusinessException(message);
            }
            String nit = firstNonBlank(text(node.get("nit")), requestedTaxId);
            String nombre = firstNonBlank(
                    text(node.get("nombre")),
                    text(node.get("name")),
                    text(node.get("razon_social"))
            );
            if (isBlank(nombre)) {
                throw new BusinessException("El NIT no tiene nombre registrado en la consulta.");
            }
            return TaxpayerLookupResponse.builder()
                    .taxId(nit)
                    .customerName(FelTaxpayerNameFormatter.format(nombre))
                    .build();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            if (trimmed.contains(",")) {
                return parseCommaSeparated(trimmed, requestedTaxId);
            }
            throw new BusinessException("No se pudo interpretar la respuesta de consulta NIT.");
        }
    }

    private TaxpayerLookupResponse parseCommaSeparated(String raw, String requestedTaxId) throws BusinessException {
        String[] parts = raw.split(",", 2);
        if (parts.length < 2) {
            throw new BusinessException("Formato de respuesta NIT inválido.");
        }
        String nit = normalizeTaxId(parts[0]);
        String nombre = parts[1].trim();
        if (isBlank(nit) || isBlank(nombre)) {
            throw new BusinessException("La consulta NIT no devolvió datos completos.");
        }
        return TaxpayerLookupResponse.builder()
                .taxId(firstNonBlank(nit, requestedTaxId))
                .customerName(FelTaxpayerNameFormatter.format(nombre))
                .build();
    }

    private static String normalizeTaxId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty() || "CF".equals(trimmed) || "C/F".equals(trimmed)) {
            return "CF";
        }
        return trimmed.replaceAll("[^0-9A-Z]", "");
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
