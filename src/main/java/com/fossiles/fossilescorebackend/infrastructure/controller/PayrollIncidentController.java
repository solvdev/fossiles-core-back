package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollIncidentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollIncidentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollIncidentWorkspaceResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.PayrollIncidentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Novedades de personal (/api/payroll/incidents): horas extra, feriados trabajados, ausencias y vacaciones. Cada
 * usuario ve y registra solo a las personas de su sección; lo valida PayrollIncidentService.
 */
@RestController
@RequestMapping("/api/payroll/incidents")
@RequiredArgsConstructor
public class PayrollIncidentController {

    private final PayrollIncidentService incidentService;

    /** A quién puede registrar el usuario, los feriados del año y los recargos vigentes. */
    @GetMapping("/workspace")
    public ResponseEntity<PayrollIncidentWorkspaceResponse> workspace(@RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(incidentService.workspace(year));
    }

    @GetMapping
    public ResponseEntity<List<PayrollIncidentResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(incidentService.list(from, to));
    }

    @PostMapping
    public ResponseEntity<List<PayrollIncidentResponse>> create(@Valid @RequestBody PayrollIncidentRequest request)
            throws BusinessException {
        return ResponseEntity.ok(incidentService.create(request));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PayrollIncidentResponse> cancel(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(incidentService.cancel(id));
    }
}
