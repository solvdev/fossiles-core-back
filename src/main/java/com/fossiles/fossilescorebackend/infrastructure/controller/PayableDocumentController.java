package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.DocImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.RentGenerationResult;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.PayableDocumentService;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.format.DateTimeFormatter;

/** Documentos por pagar: captura a mano, saldos iniciales por Excel y rentas del mes. */
@RestController
@RequestMapping("/api/payable-documents")
@RequiredArgsConstructor
public class PayableDocumentController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final PayableDocumentService service;

    public record VoidRequest(String reason) {
    }

    public record RentsRequest(String month) {
    }

    @PostMapping
    public ResponseEntity<PayablesResponse> create(@RequestBody PayableDocumentService.CreateRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.create(request));
    }

    @PostMapping("/{id}/void")
    public ResponseEntity<PayablesResponse> voidDocument(@PathVariable Long id, @RequestBody VoidRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.voidDocument(id, request.reason()));
    }

    @PostMapping("/generate-rents")
    public ResponseEntity<RentGenerationResult> generateRents(@RequestBody RentsRequest request) throws BusinessException {
        return ResponseEntity.ok(service.generateRents(request.month()));
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() throws BusinessException {
        byte[] bytes = service.buildTemplate();
        String fileName = "saldos-por-pagar-"
                + GuatemalaDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(bytes);
    }

    /** {@code dryRun=true} (por defecto) solo revisa el archivo; {@code dryRun=false} lo aplica. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocImportResult> importOpening(@RequestPart("file") MultipartFile file,
                                                         @RequestParam(defaultValue = "true") boolean dryRun)
            throws BusinessException {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo subido.");
        }
        return ResponseEntity.ok(service.importOpening(content, !dryRun));
    }
}
