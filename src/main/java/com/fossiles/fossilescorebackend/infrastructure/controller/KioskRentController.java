package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ListResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RentRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskRentService;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Contabilidad > Rentas: kioscos (el monto sale de Finanzas) y otras rentas (oficina, bodega u otra). */
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

    public record OtherSaveRequest(String name, String description, String category, String landlordName,
                                   BigDecimal monthlyAmount, Integer paymentDay,
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate contractStart,
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate contractEnd, String note) {
    }

    /** {@code rentType} opcional: KIOSK u OTHER filtra las filas. El resumen se calcula con todas. */
    @GetMapping
    public ResponseEntity<ListResponse> list(@RequestParam(required = false) String rentType) throws BusinessException {
        return ResponseEntity.ok(service.list(rentType));
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

    @PostMapping("/others")
    public ResponseEntity<RentRow> createOther(@RequestBody OtherSaveRequest request) throws BusinessException {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOther(otherValues(request)));
    }

    @PutMapping("/others/{rentId}")
    public ResponseEntity<RentRow> updateOther(@PathVariable Long rentId, @RequestBody OtherSaveRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.updateOther(rentId, otherValues(request)));
    }

    /** Desactiva la renta ({@code active=false}). No borra la fila. */
    @DeleteMapping("/others/{rentId}")
    public ResponseEntity<Void> deleteOther(@PathVariable Long rentId) throws BusinessException {
        service.deleteOther(rentId);
        return ResponseEntity.noContent().build();
    }

    private static KioskRentService.OtherValues otherValues(OtherSaveRequest request) {
        if (request == null) {
            return new KioskRentService.OtherValues(null, null, null, null, null, null, null, null, null);
        }
        return new KioskRentService.OtherValues(request.name(), request.description(), request.category(),
                request.landlordName(), request.monthlyAmount(), request.paymentDay(), request.contractStart(),
                request.contractEnd(), request.note());
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
