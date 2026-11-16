package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ListResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskRentService;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Contabilidad > Rentas: arrendador, día de pago y contrato de cada kiosco (el monto sale de Finanzas). */
@RestController
@RequestMapping("/api/kiosk-rents")
@RequiredArgsConstructor
public class KioskRentController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final KioskRentService service;

    public record SaveRequest(String landlordName, Integer paymentDay,
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate contractStart,
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate contractEnd, String note) {
    }

    @GetMapping
    public ResponseEntity<ListResponse> list() throws BusinessException {
        return ResponseEntity.ok(service.list());
    }

    @PutMapping("/{locationId}")
    public ResponseEntity<ListResponse> save(@PathVariable Long locationId, @RequestBody SaveRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.save(locationId, new KioskRentService.Values(request.landlordName(),
                request.paymentDay(), request.contractStart(), request.contractEnd(), request.note())));
    }

    @DeleteMapping("/{locationId}")
    public ResponseEntity<ListResponse> clear(@PathVariable Long locationId) throws BusinessException {
        return ResponseEntity.ok(service.clear(locationId));
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() throws BusinessException {
        byte[] bytes = service.buildTemplate();
        String fileName = "rentas-kioscos-"
                + GuatemalaDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(bytes);
    }

    /** {@code dryRun=true} (por defecto) solo revisa el archivo; {@code dryRun=false} lo aplica. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importTemplate(@RequestPart("file") MultipartFile file,
                                                       @RequestParam(defaultValue = "true") boolean dryRun)
            throws BusinessException {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo subido.");
        }
        return ResponseEntity.ok(service.importTemplate(content, !dryRun));
    }
}
