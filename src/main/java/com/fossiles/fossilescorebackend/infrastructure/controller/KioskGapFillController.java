package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskGapFillResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskGapFillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Corrección de "días sin sistema" desde el Excel del reporte. Autorización (KIOSCOS.FINANZAS.IMPORTAR) en el servicio.
 */
@RestController
@RequestMapping("/api/kiosk-financials/imports/gap-fill")
@RequiredArgsConstructor
public class KioskGapFillController {

    private final KioskGapFillService gapFillService;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KioskGapFillResponse> preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month) throws BusinessException {
        return ResponseEntity.ok(gapFillService.preview(file, year, month));
    }

    /** siteIds: ids de sitio separados por comas (los kioscos elegidos en la vista previa). */
    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KioskGapFillResponse.Applied> commit(
            @RequestPart("file") MultipartFile file,
            @RequestParam("siteIds") String siteIds,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month) throws BusinessException {
        return ResponseEntity.ok(gapFillService.commit(file, year, month, parseIds(siteIds)));
    }

    private static Set<Long> parseIds(String csv) throws BusinessException {
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : csv == null ? new String[0] : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(p));
            } catch (NumberFormatException e) {
                throw new BusinessException("siteIds inválido: '" + p + "'.");
            }
        }
        return ids;
    }
}
