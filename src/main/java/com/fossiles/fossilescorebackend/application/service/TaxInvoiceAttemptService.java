package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.FelCertificationResult;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceAttemptResponse;
import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceAttemptEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceAttemptRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.FelIvaCalculator;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceAttemptService {

    private final TaxInvoiceAttemptRepository attemptRepository;
    private final FelEmissionProperties felEmissionProperties;
    private final SecurityUtil securityUtil;
    private final ObjectMapper objectMapper;

    @Transactional
    public TaxInvoiceAttemptEntity recordCertificationAttempt(
            TaxInvoiceEntity invoice,
            TaxInvoiceDocument document,
            String action
    ) {
        int attemptNumber = attemptRepository.countByTaxInvoiceId(invoice.getId()) + 1;
        List<TaxInvoiceAttemptResponse.LineSnapshot> lineSnapshots = buildLineSnapshots(document);
        String linesJson = serializeLines(lineSnapshots);

        TaxInvoiceAttemptEntity attempt = TaxInvoiceAttemptEntity.builder()
                .taxInvoiceId(invoice.getId())
                .attemptNumber(attemptNumber)
                .action(action)
                .status(invoice.getStatus())
                .sourceType(invoice.getSourceType())
                .sourceId(invoice.getSourceId())
                .internalNumber(invoice.getInternalNumber())
                .customerTaxId(document.getCustomerTaxId())
                .customerName(document.getCustomerName())
                .address(document.getAddress())
                .phone(document.getPhone())
                .email(document.getEmail())
                .subtotal(nz(document.getSubtotal()))
                .discountAmount(nz(document.getDiscountAmount()))
                .taxAmount(sumTax(document))
                .totalAmount(nz(document.getTotalAmount()))
                .felEnabled(felEmissionProperties.isEnabled())
                .felTransactionId(invoice.getFelTransactionId())
                .felUuid(invoice.getFelUuid())
                .felSerie(invoice.getFelSerie())
                .felNumero(invoice.getFelNumero())
                .felError(invoice.getFelError())
                .linesJson(linesJson)
                .createdBy(securityUtil.getCurrentUserId())
                .build();

        return attemptRepository.save(attempt);
    }

    @Transactional
    public TaxInvoiceAttemptEntity recordVoidAttempt(
            TaxInvoiceEntity invoice,
            String reason,
            FelCertificationResult result
    ) {
        int attemptNumber = attemptRepository.countByTaxInvoiceId(invoice.getId()) + 1;
        TaxInvoiceAttemptEntity attempt = TaxInvoiceAttemptEntity.builder()
                .taxInvoiceId(invoice.getId())
                .attemptNumber(attemptNumber)
                .action("VOID")
                .status(invoice.getStatus())
                .sourceType(invoice.getSourceType())
                .sourceId(invoice.getSourceId())
                .internalNumber(invoice.getInternalNumber())
                .customerTaxId(invoice.getCustomerTaxId())
                .customerName(invoice.getCustomerName())
                .address(invoice.getAddress())
                .phone(invoice.getPhone())
                .email(invoice.getEmail())
                .subtotal(nz(invoice.getSubtotal()))
                .discountAmount(nz(invoice.getDiscountAmount()))
                .taxAmount(nz(invoice.getTaxAmount()))
                .totalAmount(nz(invoice.getTotalAmount()))
                .felEnabled(felEmissionProperties.isEnabled())
                .felTransactionId(invoice.getFelTransactionId())
                .felUuid(result != null ? result.getUuid() : invoice.getFelVoidUuid())
                .felSerie(invoice.getFelSerie())
                .felNumero(invoice.getFelNumero())
                .felError(reason)
                .linesJson("[]")
                .createdBy(securityUtil.getCurrentUserId())
                .build();
        return attemptRepository.save(attempt);
    }

    @Transactional(readOnly = true)
    public List<TaxInvoiceAttemptResponse> listByInvoiceId(Long taxInvoiceId) {
        return attemptRepository.findByTaxInvoiceIdOrderByAttemptNumberDesc(taxInvoiceId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public TaxInvoiceAttemptResponse toResponse(TaxInvoiceAttemptEntity entity) {
        return TaxInvoiceAttemptResponse.builder()
                .id(entity.getId())
                .attemptNumber(entity.getAttemptNumber())
                .action(entity.getAction())
                .status(entity.getStatus())
                .sourceType(entity.getSourceType())
                .sourceId(entity.getSourceId())
                .internalNumber(entity.getInternalNumber())
                .customerTaxId(entity.getCustomerTaxId())
                .customerName(entity.getCustomerName())
                .address(entity.getAddress())
                .phone(entity.getPhone())
                .email(entity.getEmail())
                .subtotal(entity.getSubtotal())
                .discountAmount(entity.getDiscountAmount())
                .taxAmount(entity.getTaxAmount())
                .totalAmount(entity.getTotalAmount())
                .felEnabled(entity.getFelEnabled())
                .felTransactionId(entity.getFelTransactionId())
                .felUuid(entity.getFelUuid())
                .felSerie(entity.getFelSerie())
                .felNumero(entity.getFelNumero())
                .felError(entity.getFelError())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .lines(deserializeLines(entity.getLinesJson()))
                .build();
    }

    private List<TaxInvoiceAttemptResponse.LineSnapshot> buildLineSnapshots(TaxInvoiceDocument document) {
        List<TaxInvoiceAttemptResponse.LineSnapshot> snapshots = new ArrayList<>();
        if (document.getLines() == null) {
            return snapshots;
        }
        int lineNo = 0;
        for (TaxInvoiceDocument.Line line : document.getLines()) {
            lineNo++;
            snapshots.add(TaxInvoiceAttemptResponse.LineSnapshot.builder()
                    .lineNumber(lineNo)
                    .description(line.getDescription())
                    .quantity(nz(line.getQuantity()))
                    .unitPrice(nz(line.getUnitPrice()))
                    .lineTotal(nz(line.getLineTotal()))
                    .build());
        }
        return snapshots;
    }

    private String serializeLines(List<TaxInvoiceAttemptResponse.LineSnapshot> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JsonProcessingException ex) {
            log.warn("No se pudo serializar líneas de bitácora FEL: {}", ex.getMessage());
            return "[]";
        }
    }

    private List<TaxInvoiceAttemptResponse.LineSnapshot> deserializeLines(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TaxInvoiceAttemptResponse.LineSnapshot>>() {});
        } catch (JsonProcessingException ex) {
            log.warn("No se pudo deserializar líneas de bitácora FEL: {}", ex.getMessage());
            return List.of();
        }
    }

    private BigDecimal sumTax(TaxInvoiceDocument document) {
        BigDecimal total = BigDecimal.ZERO;
        if (document.getLines() == null) {
            return total;
        }
        for (TaxInvoiceDocument.Line line : document.getLines()) {
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(nz(line.getLineTotal()));
            total = total.add(iva.tax());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
