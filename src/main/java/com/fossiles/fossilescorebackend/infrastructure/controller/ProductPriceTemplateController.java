package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.ProductPriceTemplateService;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Plantilla Excel de precios de venta: se descarga con el precio actual y se sube con el precio nuevo.
 * Cuelga de {@code /api/products/**}, así que cada subida exitosa invalida la caché del catálogo.
 */
@RestController
@RequestMapping("/api/products/price-template")
@RequiredArgsConstructor
public class ProductPriceTemplateController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ProductPriceTemplateService service;

    @GetMapping
    public ResponseEntity<byte[]> download(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) List<Long> categoryIds,
            @RequestParam(defaultValue = "kiosco") String tipo) throws BusinessException {
        List<Long> ids = new ArrayList<>();
        if (categoryIds != null) {
            categoryIds.stream().filter(id -> id != null).forEach(ids::add);
        }
        if (categoryId != null && !ids.contains(categoryId)) {
            ids.add(categoryId);
        }
        byte[] bytes = service.buildTemplate(ids, tipo);
        String prefix = "entrecueros".equalsIgnoreCase(tipo)
                ? "plantilla-precios-entrecueros-"
                : "plantilla-precios-kioscos-";
        String fileName = prefix
                + GuatemalaDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(bytes);
    }

    /** {@code dryRun=true} (por defecto) solo revisa el archivo; {@code dryRun=false} aplica los precios. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductPriceTemplateService.ImportResult> importTemplate(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun) throws BusinessException {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo subido.");
        }
        return ResponseEntity.ok(service.importTemplate(content, !dryRun));
    }
}
