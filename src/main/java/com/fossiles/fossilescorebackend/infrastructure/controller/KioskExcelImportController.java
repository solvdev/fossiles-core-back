package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioskExcelCommitRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelBatchSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelCommitResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelPreviewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.KioskExcelImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Importación de los Excel mensuales de ventas/costos por kiosco.
 * La autorización (KIOSCOS.FINANZAS.IMPORTAR) se valida en el servicio.
 */
@RestController
@RequestMapping("/api/kiosk-financials/imports")
@RequiredArgsConstructor
public class KioskExcelImportController {

    private final KioskExcelImportService importService;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KioskExcelPreviewResponse> preview(@RequestPart("files") List<MultipartFile> files)
            throws BusinessException {
        return ResponseEntity.ok(importService.preview(files));
    }

    @PostMapping("/commit")
    public ResponseEntity<KioskExcelCommitResponse> commit(@RequestBody KioskExcelCommitRequest request)
            throws BusinessException {
        return ResponseEntity.ok(importService.commit(request));
    }

    @PostMapping("/{batchId}/revert")
    public ResponseEntity<KioskExcelBatchSummaryResponse> revert(@PathVariable Long batchId)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(importService.revert(batchId));
    }

    @GetMapping
    public ResponseEntity<List<KioskExcelBatchSummaryResponse>> list() throws BusinessException {
        return ResponseEntity.ok(importService.list());
    }
}
