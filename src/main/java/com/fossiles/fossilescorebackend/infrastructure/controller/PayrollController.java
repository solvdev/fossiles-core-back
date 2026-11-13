package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.EmployeeChargeSettlementRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollAnnulRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollChequeAssignRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollParameterRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollPayDateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollProfileRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.EmployeeChargeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollParameterResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollPayDayResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollRunResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.EmployeeChargeService;
import com.fossiles.fossilescorebackend.application.service.PayrollParameterService;
import com.fossiles.fossilescorebackend.application.service.PayrollProfileService;
import com.fossiles.fossilescorebackend.application.service.PayrollRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API de planilla (/api/payroll): planillas, días de pago, fichas, parámetros y cargos a colaboradores. Los permisos
 * los valida PayrollAccessGuard en cada servicio.
 */
@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
public class PayrollController {

    private final PayrollRunService runService;
    private final PayrollParameterService parameterService;
    private final EmployeeChargeService chargeService;
    private final PayrollProfileService profileService;

    @GetMapping("/pay-days")
    public ResponseEntity<List<PayrollPayDayResponse>> payDays(
            @RequestParam(required = false) String payGroup,
            @RequestParam(defaultValue = "6") int count) throws BusinessException {
        return ResponseEntity.ok(runService.payDays(payGroup, count));
    }

    @GetMapping("/runs")
    public ResponseEntity<List<PayrollRunResponse>> listRuns() {
        return ResponseEntity.ok(runService.list());
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<PayrollRunResponse> getRun(@PathVariable Long id) throws ResourceNotFoundException {
        return ResponseEntity.ok(runService.get(id));
    }

    @PostMapping("/runs")
    public ResponseEntity<PayrollRunResponse> createRun(@Valid @RequestBody PayrollRunCreateRequest request)
            throws BusinessException {
        return ResponseEntity.ok(runService.create(request));
    }

    @PostMapping("/runs/{id}/recalculate")
    public ResponseEntity<PayrollRunResponse> recalculate(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(runService.recalculate(id));
    }

    @PostMapping("/runs/{id}/approve")
    public ResponseEntity<PayrollRunResponse> approve(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(runService.approve(id));
    }

    @PostMapping("/runs/{id}/cheques")
    public ResponseEntity<PayrollRunResponse> assignCheques(@PathVariable Long id,
            @Valid @RequestBody PayrollChequeAssignRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(runService.assignCheques(id, request));
    }

    @PostMapping("/runs/{id}/pay")
    public ResponseEntity<PayrollRunResponse> pay(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(runService.pay(id));
    }

    @PostMapping("/runs/{id}/pay-date")
    public ResponseEntity<PayrollRunResponse> changePayDate(@PathVariable Long id,
            @Valid @RequestBody PayrollPayDateRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(runService.changePayDate(id, request.getPayDate()));
    }

    @GetMapping("/profiles")
    public ResponseEntity<List<PayrollProfileResponse>> profiles() {
        return ResponseEntity.ok(profileService.list());
    }

    @PutMapping("/profiles/{employeeId}")
    public ResponseEntity<PayrollProfileResponse> updateProfile(@PathVariable Long employeeId,
            @Valid @RequestBody PayrollProfileRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(profileService.update(employeeId, request));
    }

    @PostMapping("/runs/{id}/annul")
    public ResponseEntity<PayrollRunResponse> annul(@PathVariable Long id,
            @Valid @RequestBody PayrollAnnulRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(runService.annul(id, request.getReason()));
    }

    @GetMapping("/parameters")
    public ResponseEntity<List<PayrollParameterResponse>> parameters() {
        return ResponseEntity.ok(parameterService.list());
    }

    @PostMapping("/parameters")
    public ResponseEntity<PayrollParameterResponse> addParameter(@Valid @RequestBody PayrollParameterRequest request)
            throws BusinessException {
        return ResponseEntity.ok(parameterService.addVersion(request));
    }

    @GetMapping("/charges")
    public ResponseEntity<List<EmployeeChargeResponse>> charges(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long employeeId) {
        return ResponseEntity.ok(chargeService.list(status, employeeId));
    }

    @PostMapping("/charges/{id}/settlement")
    public ResponseEntity<EmployeeChargeResponse> decide(@PathVariable Long id,
            @Valid @RequestBody EmployeeChargeSettlementRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(chargeService.decide(id, request.getSettlementMethod()));
    }

    @PostMapping("/charges/{id}/mark-paid")
    public ResponseEntity<EmployeeChargeResponse> markPaid(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(chargeService.markPaid(id));
    }

    @PostMapping("/charges/{id}/cancel")
    public ResponseEntity<EmployeeChargeResponse> cancel(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(chargeService.cancel(id));
    }
}
