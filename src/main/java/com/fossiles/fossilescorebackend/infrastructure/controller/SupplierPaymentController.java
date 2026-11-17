package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.BankAccountDto;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ChequeLayoutDto;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ChequePrintItem;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ConfigResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentMethodDto;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.SupplierPaymentConfigService;
import com.fossiles.fossilescorebackend.application.service.SupplierPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Contabilidad > Cuentas por pagar, Pagos a proveedores y Bancos y formas de pago. */
@RestController
@RequestMapping("/api/supplier-payments")
@RequiredArgsConstructor
public class SupplierPaymentController {

    private final SupplierPaymentService service;
    private final SupplierPaymentConfigService configService;

    public record VoidRequest(String reason) {
    }

    public record IdsRequest(List<Long> ids) {
    }

    public record StartDateRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    }

    // ---- Cuentas por pagar y pagos

    @GetMapping("/payables")
    public ResponseEntity<PayablesResponse> payables() throws BusinessException {
        return ResponseEntity.ok(service.payables());
    }

    @GetMapping("/payments")
    public ResponseEntity<PaymentsResponse> payments() throws BusinessException {
        return ResponseEntity.ok(service.listPayments());
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentRow> create(@RequestBody SupplierPaymentService.CreateRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.createPayment(request));
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<PaymentRow> voidPayment(@PathVariable Long id, @RequestBody VoidRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.voidPayment(id, request.reason()));
    }

    /** Datos para dibujar los cheques en el navegador. No cambia nada. */
    @PostMapping("/payments/print-data")
    public ResponseEntity<List<ChequePrintItem>> printData(@RequestBody IdsRequest request) throws BusinessException {
        return ResponseEntity.ok(service.printData(request.ids()));
    }

    /** Se llama después de imprimir: marca los cheques como impresos y devuelve la lista actualizada. */
    @PostMapping("/payments/mark-printed")
    public ResponseEntity<PaymentsResponse> markPrinted(@RequestBody IdsRequest request) throws BusinessException {
        return ResponseEntity.ok(service.markPrinted(request.ids()));
    }

    // ---- Configuración

    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> config() throws BusinessException {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PutMapping("/config/payables-start")
    public ResponseEntity<ConfigResponse> payablesStart(@RequestBody StartDateRequest request) throws BusinessException {
        return ResponseEntity.ok(configService.savePayablesStart(request.date()));
    }

    @PostMapping("/config/layouts")
    public ResponseEntity<ConfigResponse> createLayout(@RequestBody ChequeLayoutDto dto) throws BusinessException {
        return ResponseEntity.ok(configService.saveLayout(null, dto));
    }

    @PutMapping("/config/layouts/{id}")
    public ResponseEntity<ConfigResponse> updateLayout(@PathVariable Long id, @RequestBody ChequeLayoutDto dto)
            throws BusinessException {
        return ResponseEntity.ok(configService.saveLayout(id, dto));
    }

    @PostMapping("/config/accounts")
    public ResponseEntity<ConfigResponse> createAccount(@RequestBody BankAccountDto dto) throws BusinessException {
        return ResponseEntity.ok(configService.saveAccount(null, dto));
    }

    @PutMapping("/config/accounts/{id}")
    public ResponseEntity<ConfigResponse> updateAccount(@PathVariable Long id, @RequestBody BankAccountDto dto)
            throws BusinessException {
        return ResponseEntity.ok(configService.saveAccount(id, dto));
    }

    @PostMapping("/config/methods")
    public ResponseEntity<ConfigResponse> createMethod(@RequestBody PaymentMethodDto dto) throws BusinessException {
        return ResponseEntity.ok(configService.saveMethod(null, dto));
    }

    @PutMapping("/config/methods/{id}")
    public ResponseEntity<ConfigResponse> updateMethod(@PathVariable Long id, @RequestBody PaymentMethodDto dto)
            throws BusinessException {
        return ResponseEntity.ok(configService.saveMethod(id, dto));
    }
}
