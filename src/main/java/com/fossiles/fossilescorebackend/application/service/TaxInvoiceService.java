package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ManualTaxInvoiceRequest;
import com.fossiles.fossilescorebackend.application.dto.response.FelCertificationResult;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceAttemptResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceCertifiedXmlDownload;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxpayerLookupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.FelIvaCalculator;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoiceService {

    private static final Set<String> NON_INVOICEABLE_ONLINE_STATUSES = Set.of(
            "ANULADA", "CANCELADO", "DEVOLUCION"
    );

    private final TaxInvoiceRepository taxInvoiceRepository;
    private final KioskSaleRepository kioskSaleRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final LocationRepository locationRepository;
    private final OnlineSaleService onlineSaleService;
    private final KioskSaleInvoiceMapper kioskSaleInvoiceMapper;
    private final OnlineSaleInvoiceMapper onlineSaleInvoiceMapper;
    private final FelEmissionProperties properties;
    private final FelFactXmlBuilder factXmlBuilder;
    private final FelAnulacionXmlBuilder anulacionXmlBuilder;
    private final FelSignerService signerService;
    private final FelCertificationService certificationService;
    private final FelReceptorLookupService receptorLookupService;
    private final SecurityUtil securityUtil;
    private final TaxInvoiceAttemptService taxInvoiceAttemptService;

    @Transactional
    public TaxInvoiceResponse issueFromKioskSale(KioskSaleEntity sale, boolean requestInvoice)
            throws BusinessException {
        if (!KioskSaleInvoiceMapper.shouldEmitForPos(sale.getCustomerTaxId(), requestInvoice)) {
            return null;
        }

        Optional<TaxInvoiceEntity> existing = taxInvoiceRepository.findBySourceTypeAndSourceId("KIOSK_SALE", sale.getId());
        if (existing.isPresent()) {
            TaxInvoiceEntity invoice = existing.get();
            if ("CERTIFIED".equals(invoice.getStatus())) {
                return toResponse(invoice);
            }
            if ("FAILED".equals(invoice.getStatus()) || "DRAFT".equals(invoice.getStatus())
                    || "SKIPPED".equals(invoice.getStatus())) {
                try {
                    return retry(invoice.getId());
                } catch (ResourceNotFoundException ex) {
                    throw new BusinessException("No se encontró la factura para reintento.");
                }
            }
        }

        assertNoCertifiedInvoice("KIOSK_SALE", sale.getId());
        TaxInvoiceDocument document = kioskSaleInvoiceMapper.fromSale(sale);
        enrichEmitterFromKioskLocation(document, sale.getKioskLocationId());
        validateDocument(document);
        TaxInvoiceEntity invoice = persistDraft("KIOSK_SALE", sale.getId(), document, sale.getCreatedBy());
        certify(invoice, document, false);
        sale.setInvoiceId(invoice.getId());
        syncKioskSaleFelFields(sale, invoice);
        kioskSaleRepository.save(sale);
        return toResponse(invoice);
    }

    @Transactional
    public TaxInvoiceResponse issueFromKioskSaleId(Long saleId) throws BusinessException, ResourceNotFoundException {
        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        boolean requestInvoice = KioskSaleInvoiceMapper.shouldEmitForPos(sale.getCustomerTaxId(), true);
        TaxInvoiceResponse response = issueFromKioskSale(sale, requestInvoice);
        if (response == null) {
            throw new BusinessException("Esta venta POS no requiere factura electrónica (CF sin solicitud).");
        }
        return response;
    }

    @Transactional
    public TaxInvoiceResponse voidInvoice(Long invoiceId, String reason) throws BusinessException, ResourceNotFoundException {
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("Debes indicar el motivo de anulación.");
        }
        TaxInvoiceEntity invoice = taxInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxInvoice", invoiceId));
        if ("VOID".equalsIgnoreCase(safe(invoice.getStatus()))) {
            throw new BusinessException("La factura ya está anulada.");
        }
        if (!"CERTIFIED".equalsIgnoreCase(safe(invoice.getStatus()))) {
            throw new BusinessException("Solo se pueden anular facturas certificadas.");
        }
        if (invoice.getFelUuid() == null || invoice.getFelUuid().isBlank()) {
            throw new BusinessException("La factura no tiene UUID FEL para anular.");
        }

        String trimmedReason = reason.trim();
        if (!properties.isEnabled()) {
            markInvoiceVoidLocal(invoice, trimmedReason, null);
            taxInvoiceRepository.save(invoice);
            syncSourceFelFields(invoice);
            return toResponse(invoice);
        }

        validateEmitterConfig();
        ZonedDateTime originalEmission = invoice.getFelCertifiedAt() != null
                ? invoice.getFelCertifiedAt().atZone(ZoneId.of("America/Guatemala"))
                : invoice.getIssuedAt() != null
                ? invoice.getIssuedAt().atZone(ZoneId.of("America/Guatemala"))
                : ZonedDateTime.now(ZoneId.of("America/Guatemala"));

        String transactionId = "VOID-" + invoice.getId() + "-" + System.currentTimeMillis();
        String unsignedXml = anulacionXmlBuilder.buildUnsignedAnulacionXml(
                invoice.getFelUuid(),
                properties.getNitEmisor(),
                invoice.getCustomerTaxId(),
                originalEmission,
                trimmedReason
        );
        String signedXml = signerService.signXml(unsignedXml, transactionId, true);
        FelCertificationResult result = certificationService.certifyAnnulmentSignedXml(signedXml, transactionId);

        if (!"CERTIFIED".equals(result.getStatus())) {
            String msg = result.getErrorMessage() != null ? result.getErrorMessage() : "Anulación FEL rechazada.";
            throw new BusinessException("No se pudo anular la factura electrónica: " + msg);
        }

        markInvoiceVoidLocal(invoice, trimmedReason, result.getUuid());
        taxInvoiceRepository.save(invoice);
        syncSourceFelFields(invoice);
        try {
            taxInvoiceAttemptService.recordVoidAttempt(invoice, trimmedReason, result);
        } catch (Exception ex) {
            log.error("No se pudo registrar bitácora VOID para factura {}: {}", invoice.getId(), ex.getMessage());
        }
        return toResponse(invoice);
    }

    private void markInvoiceVoidLocal(TaxInvoiceEntity invoice, String reason, String voidUuid) {
        invoice.setStatus("VOID");
        invoice.setVoidedAt(LocalDateTime.now());
        invoice.setVoidReason(reason);
        invoice.setFelVoidUuid(voidUuid);
    }

    @Transactional
    public TaxInvoiceResponse issueFromOnlineSale(Long saleId) throws BusinessException, ResourceNotFoundException {
        OnlineSaleEntity sale = onlineSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("OnlineSale", saleId));
        onlineSaleService.recalculateAmountsFromItems(sale);
        sale = onlineSaleRepository.save(sale);
        assertOnlineSaleInvoiceable(sale);

        Optional<TaxInvoiceEntity> existing = taxInvoiceRepository.findBySourceTypeAndSourceId("ONLINE_SALE", sale.getId());
        if (existing.isPresent()) {
            TaxInvoiceEntity invoice = existing.get();
            if ("CERTIFIED".equals(invoice.getStatus())) {
                throw new BusinessException("Esta venta online ya tiene factura certificada.");
            }
            if ("FAILED".equals(invoice.getStatus()) || "DRAFT".equals(invoice.getStatus())
                    || "SKIPPED".equals(invoice.getStatus())) {
                try {
                    return retry(invoice.getId());
                } catch (ResourceNotFoundException ex) {
                    throw new BusinessException("No se encontró la factura para reintento.");
                }
            }
        }

        assertNoCertifiedInvoice("ONLINE_SALE", sale.getId());

        TaxInvoiceDocument document = onlineSaleInvoiceMapper.fromSale(sale);
        enrichEmitterForCueroGlamCentral(document);
        enrichReceptorFromLookup(document);
        validateDocument(document);

        TaxInvoiceEntity invoice = persistDraft("ONLINE_SALE", sale.getId(), document, sale.getCreatedBy());
        certify(invoice, document, false);
        sale.setInvoiceId(invoice.getId());
        onlineSaleRepository.save(sale);
        return toResponse(invoice);
    }

    @Transactional
    public TaxInvoiceResponse issueManual(ManualTaxInvoiceRequest request) throws BusinessException {
        TaxInvoiceDocument document = buildManualDocument(request);
        enrichEmitterForCueroGlamCentral(document);
        enrichReceptorFromLookup(document);
        validateDocument(document);
        Long userId = securityUtil.getCurrentUserId();
        TaxInvoiceEntity invoice = persistDraft("MANUAL", null, document, userId);
        invoice.setNotes(trimToNull(request.getNotes()));
        taxInvoiceRepository.save(invoice);
        certify(invoice, document, false);
        return toResponse(invoice);
    }

    @Transactional
    public TaxInvoiceResponse retry(Long invoiceId) throws BusinessException, ResourceNotFoundException {
        TaxInvoiceEntity invoice = taxInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxInvoice", invoiceId));
        if ("CERTIFIED".equals(invoice.getStatus())) {
            throw new BusinessException("La factura ya está certificada.");
        }
        if ("VOID".equalsIgnoreCase(safe(invoice.getStatus()))) {
            throw new BusinessException("No se puede certificar una factura anulada.");
        }
        TaxInvoiceDocument document = rebuildDocumentForRetry(invoice);
        syncInvoiceFromDocument(invoice, document);
        certify(invoice, document, true);
        syncSourceFelFields(invoice);
        return getById(invoiceId);
    }

    private TaxInvoiceDocument rebuildDocumentForRetry(TaxInvoiceEntity invoice)
            throws BusinessException, ResourceNotFoundException {
        if ("ONLINE_SALE".equals(invoice.getSourceType()) && invoice.getSourceId() != null) {
            OnlineSaleEntity sale = onlineSaleRepository.findById(invoice.getSourceId())
                    .orElseThrow(() -> new ResourceNotFoundException("OnlineSale", invoice.getSourceId()));
            onlineSaleService.recalculateAmountsFromItems(sale);
            onlineSaleRepository.save(sale);
            TaxInvoiceDocument document = onlineSaleInvoiceMapper.fromSale(sale);
            enrichEmitterForCueroGlamCentral(document);
            enrichReceptorFromLookup(document);
            return document;
        }
        if ("KIOSK_SALE".equals(invoice.getSourceType()) && invoice.getSourceId() != null) {
            KioskSaleEntity sale = kioskSaleRepository.findById(invoice.getSourceId())
                    .orElseThrow(() -> new ResourceNotFoundException("KioskSale", invoice.getSourceId()));
            TaxInvoiceDocument document = kioskSaleInvoiceMapper.fromSale(sale);
            enrichEmitterFromKioskLocation(document, sale.getKioskLocationId());
            return document;
        }
        return toDocument(invoice);
    }

    private void syncInvoiceFromDocument(TaxInvoiceEntity invoice, TaxInvoiceDocument document) {
        invoice.setCustomerTaxId(normalizeTaxId(document.getCustomerTaxId()));
        invoice.setCustomerName(document.getCustomerName());
        invoice.setAddress(document.getAddress());
        invoice.setPhone(document.getPhone());
        invoice.setEmail(document.getEmail());
        invoice.setSubtotal(document.getSubtotal());
        invoice.setDiscountAmount(nz(document.getDiscountAmount()));
        invoice.setTotalAmount(document.getTotalAmount());
        invoice.setTaxAmount(sumTax(document));
        invoice.getLines().clear();
        int lineNo = 0;
        for (TaxInvoiceDocument.Line line : document.getLines()) {
            lineNo++;
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(nz(line.getLineTotal()));
            TaxInvoiceLineEntity entityLine = TaxInvoiceLineEntity.builder()
                    .taxInvoice(invoice)
                    .lineNumber(lineNo)
                    .description(line.getDescription())
                    .quantity(nz(line.getQuantity()))
                    .unitPrice(nz(line.getUnitPrice()))
                    .lineTotal(nz(line.getLineTotal()))
                    .gravableAmount(iva.gravable())
                    .taxAmount(iva.tax())
                    .build();
            invoice.getLines().add(entityLine);
        }
        taxInvoiceRepository.save(invoice);
    }

    @Transactional(readOnly = true)
    public TaxInvoiceResponse getById(Long id) throws ResourceNotFoundException {
        TaxInvoiceEntity invoice = taxInvoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TaxInvoice", id));
        TaxInvoiceResponse response = toResponse(invoice);
        response.setAttempts(taxInvoiceAttemptService.listByInvoiceId(id));
        return response;
    }

    @Transactional(readOnly = true)
    public List<TaxInvoiceAttemptResponse> getAttempts(Long invoiceId) throws ResourceNotFoundException {
        if (!taxInvoiceRepository.existsById(invoiceId)) {
            throw new ResourceNotFoundException("TaxInvoice", invoiceId);
        }
        return taxInvoiceAttemptService.listByInvoiceId(invoiceId);
    }

    @Transactional(readOnly = true)
    public TaxInvoiceCertifiedXmlDownload getCertifiedXmlDownload(Long id)
            throws ResourceNotFoundException, BusinessException {
        TaxInvoiceEntity invoice = taxInvoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TaxInvoice", id));
        if (!"CERTIFIED".equals(invoice.getStatus())) {
            throw new BusinessException("La factura no está certificada.");
        }
        if (!hasCertifiedXml(invoice)) {
            throw new BusinessException(
                    "No hay XML certificado almacenado. Solo aplica a facturas certificadas después de activar este respaldo.");
        }
        return TaxInvoiceCertifiedXmlDownload.builder()
                .filename(buildCertifiedXmlFilename(invoice))
                .content(invoice.getFelCertifiedXml().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .contentType("application/xml")
                .build();
    }

    @Transactional(readOnly = true)
    public boolean hasStoredCertifiedXml(Long invoiceId) {
        if (invoiceId == null) {
            return false;
        }
        return taxInvoiceRepository.findById(invoiceId)
                .map(TaxInvoiceService::hasCertifiedXml)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<TaxInvoiceResponse> list(
            String sourceType,
            String status,
            String customerTaxId,
            String certificationFilter,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        LocalDateTime from = fromDate != null ? fromDate.atStartOfDay() : null;
        LocalDateTime to = toDate != null ? toDate.atTime(LocalTime.MAX) : null;
        String normalizedSourceType = trimToNull(sourceType);
        String normalizedStatus = trimToNull(status);
        String normalizedCertificationFilter = trimToNull(certificationFilter);
        String customerTaxIdPattern = buildCustomerTaxIdPattern(customerTaxId);
        return taxInvoiceRepository.search(
                        normalizedSourceType,
                        normalizedStatus,
                        customerTaxIdPattern,
                        normalizedCertificationFilter,
                        from,
                        to
                ).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TaxInvoiceSummaryResponse getSummary() {
        long certified = 0;
        long failed = 0;
        long draft = 0;
        long skipped = 0;
        long voided = 0;
        for (Object[] row : taxInvoiceRepository.countGroupByStatus()) {
            String invoiceStatus = row[0] == null ? "" : row[0].toString();
            long count = row[1] == null ? 0L : ((Number) row[1]).longValue();
            switch (invoiceStatus) {
                case "CERTIFIED" -> certified = count;
                case "FAILED" -> failed = count;
                case "DRAFT" -> draft = count;
                case "SKIPPED" -> skipped = count;
                case "VOID" -> voided = count;
                default -> { }
            }
        }
        long unsigned = failed + draft + skipped;
        return TaxInvoiceSummaryResponse.builder()
                .total(certified + unsigned + voided)
                .certified(certified)
                .unsigned(unsigned)
                .failed(failed)
                .draft(draft)
                .skipped(skipped)
                .voided(voided)
                .build();
    }

    private String buildCustomerTaxIdPattern(String customerTaxId) {
        String normalized = trimToNull(customerTaxId);
        if (normalized == null) {
            return null;
        }
        return "%" + normalized.toUpperCase(Locale.ROOT) + "%";
    }

    @Transactional(readOnly = true)
    public TaxInvoiceResponse findBySource(String sourceType, Long sourceId) {
        return taxInvoiceRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .map(this::toResponse)
                .orElse(null);
    }

    private void assertNoCertifiedInvoice(String sourceType, Long sourceId) throws BusinessException {
        if (taxInvoiceRepository.existsBySourceTypeAndSourceIdAndStatus(sourceType, sourceId, "CERTIFIED")) {
            throw new BusinessException("Ya existe una factura certificada para esta venta.");
        }
    }

    private void assertOnlineSaleInvoiceable(OnlineSaleEntity sale) throws BusinessException {
        String status = sale.getStatus() == null ? "" : sale.getStatus().trim().toUpperCase(Locale.ROOT);
        if (NON_INVOICEABLE_ONLINE_STATUSES.contains(status)) {
            throw new BusinessException("No se puede facturar una venta en estado " + status + ".");
        }
        if (sale.getTotalAmount() == null || sale.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("La venta no tiene monto facturable.");
        }
    }

    private TaxInvoiceDocument buildManualDocument(ManualTaxInvoiceRequest request) {
        List<TaxInvoiceDocument.Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int lineNo = 0;
        for (ManualTaxInvoiceRequest.LineRequest line : request.getLines()) {
            lineNo++;
            BigDecimal qty = line.getQuantity().setScale(3, RoundingMode.HALF_UP);
            BigDecimal unitPrice = line.getUnitPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineTotal);
            lines.add(TaxInvoiceDocument.Line.builder()
                    .description(line.getDescription().trim())
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .build());
        }

        String taxId = normalizeTaxId(request.getCustomerTaxId());
        String customerName = resolveCustomerName(taxId, request.getCustomerName());

        return TaxInvoiceDocument.builder()
                .transactionId("MAN-" + System.currentTimeMillis())
                .issuedAt(LocalDateTime.now())
                .customerTaxId(taxId)
                .customerName(customerName)
                .address(trimToNull(request.getAddress()))
                .phone(trimToNull(request.getPhone()))
                .email(trimToNull(request.getEmail()))
                .subtotal(subtotal)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(subtotal)
                .lines(lines)
                .build();
    }

    private void enrichReceptorFromLookup(TaxInvoiceDocument document) throws BusinessException {
        String taxId = normalizeTaxId(document.getCustomerTaxId());
        document.setCustomerTaxId(taxId);
        if ("CF".equals(taxId)) {
            document.setCustomerName("CONSUMIDOR FINAL");
            return;
        }
        if (document.getCustomerName() != null && !document.getCustomerName().isBlank()) {
            return;
        }
        TaxpayerLookupResponse lookup = receptorLookupService.lookup(taxId);
        if (lookup.getCustomerName() != null && !lookup.getCustomerName().isBlank()) {
            document.setCustomerName(lookup.getCustomerName());
        }
    }

    private TaxInvoiceEntity persistDraft(
            String sourceType,
            Long sourceId,
            TaxInvoiceDocument document,
            Long createdBy
    ) {
        BigDecimal taxAmount = sumTax(document);
        TaxInvoiceEntity invoice = TaxInvoiceEntity.builder()
                .sourceType(sourceType)
                .sourceId(sourceId)
                .documentType("FACT")
                .status("DRAFT")
                .customerTaxId(normalizeTaxId(document.getCustomerTaxId()))
                .customerName(document.getCustomerName())
                .address(document.getAddress())
                .phone(document.getPhone())
                .email(document.getEmail())
                .subtotal(document.getSubtotal())
                .discountAmount(nz(document.getDiscountAmount()))
                .taxAmount(taxAmount)
                .totalAmount(document.getTotalAmount())
                .felTransactionId(document.getTransactionId())
                .issuedAt(document.getIssuedAt() != null ? document.getIssuedAt() : LocalDateTime.now())
                .createdBy(createdBy)
                .lines(new ArrayList<>())
                .build();

        int lineNo = 0;
        for (TaxInvoiceDocument.Line line : document.getLines()) {
            lineNo++;
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(nz(line.getLineTotal()));
            TaxInvoiceLineEntity entityLine = TaxInvoiceLineEntity.builder()
                    .taxInvoice(invoice)
                    .lineNumber(lineNo)
                    .description(line.getDescription())
                    .quantity(nz(line.getQuantity()))
                    .unitPrice(nz(line.getUnitPrice()))
                    .lineTotal(nz(line.getLineTotal()))
                    .gravableAmount(iva.gravable())
                    .taxAmount(iva.tax())
                    .build();
            invoice.getLines().add(entityLine);
        }

        TaxInvoiceEntity saved = taxInvoiceRepository.save(invoice);
        saved.setInternalNumber(String.format("TINV-%06d", saved.getId()));
        return taxInvoiceRepository.save(saved);
    }

    private void certify(TaxInvoiceEntity invoice, TaxInvoiceDocument document, boolean retry)
            throws BusinessException {
        String action = retry ? "RETRY" : "ISSUE";
        if (!properties.isEnabled()) {
            applyResult(invoice, FelCertificationResult.builder().status("SKIPPED").build());
            taxInvoiceRepository.save(invoice);
            recordCertificationAttempt(invoice, document, action);
            return;
        }

        String transactionId = retry && invoice.getFelTransactionId() != null
                ? invoice.getFelTransactionId()
                : document.getTransactionId();
        invoice.setFelTransactionId(transactionId);

        try {
            validateEmitterConfig();
            String unsignedXml = factXmlBuilder.buildUnsignedXml(document);
            String signedXml = signerService.signXml(unsignedXml, transactionId);
            FelCertificationResult result = certificationService.certifySignedXml(signedXml, transactionId);
            applyResult(invoice, result);

            if (!"CERTIFIED".equals(result.getStatus())) {
                String msg = result.getErrorMessage() != null ? result.getErrorMessage() : "Certificación FEL fallida.";
                log.warn("FEL falló para factura {}: {}", invoice.getInternalNumber(), msg);
                taxInvoiceRepository.save(invoice);
                if (properties.isRequired()) {
                    recordCertificationAttempt(invoice, document, action);
                    throw new BusinessException("No se pudo certificar la factura electrónica: " + msg);
                }
            } else {
                taxInvoiceRepository.save(invoice);
            }
        } catch (BusinessException ex) {
            applyFailure(invoice, ex.getMessage());
            taxInvoiceRepository.save(invoice);
            recordCertificationAttempt(invoice, document, action);
            if (properties.isRequired()) {
                throw ex;
            }
            log.warn("FEL error (no bloqueante) factura {}: {}", invoice.getInternalNumber(), ex.getMessage());
            return;
        } catch (Exception ex) {
            applyFailure(invoice, ex.getMessage());
            taxInvoiceRepository.save(invoice);
            recordCertificationAttempt(invoice, document, action);
            if (properties.isRequired()) {
                throw new BusinessException("Error inesperado al emitir FEL: " + ex.getMessage());
            }
            log.error("FEL error inesperado factura {}", invoice.getInternalNumber(), ex);
            return;
        }

        recordCertificationAttempt(invoice, document, action);
    }

    private void recordCertificationAttempt(
            TaxInvoiceEntity invoice,
            TaxInvoiceDocument document,
            String action
    ) {
        try {
            taxInvoiceAttemptService.recordCertificationAttempt(invoice, document, action);
        } catch (Exception ex) {
            log.error("No se pudo registrar bitácora FEL para factura {}: {}", invoice.getId(), ex.getMessage(), ex);
        }
    }

    private void syncSourceFelFields(TaxInvoiceEntity invoice) {
        if ("KIOSK_SALE".equals(invoice.getSourceType()) && invoice.getSourceId() != null) {
            kioskSaleRepository.findById(invoice.getSourceId()).ifPresent(sale -> {
                sale.setInvoiceId(invoice.getId());
                syncKioskSaleFelFields(sale, invoice);
                kioskSaleRepository.save(sale);
            });
        }
    }

    private void syncKioskSaleFelFields(KioskSaleEntity sale, TaxInvoiceEntity invoice) {
        sale.setFelStatus(invoice.getStatus());
        if ("CERTIFIED".equals(invoice.getStatus())) {
            sale.setFelUuid(invoice.getFelUuid());
            sale.setFelSerie(invoice.getFelSerie());
            sale.setFelNumero(invoice.getFelNumero());
            sale.setFelError(null);
            sale.setFelCertifiedAt(invoice.getFelCertifiedAt());
        } else if ("VOID".equals(invoice.getStatus())) {
            sale.setFelError(invoice.getVoidReason());
        } else if ("SKIPPED".equals(invoice.getStatus())) {
            sale.setFelError(null);
        } else {
            sale.setFelError(invoice.getFelError());
        }
    }

    private void applyResult(TaxInvoiceEntity invoice, FelCertificationResult result) {
        invoice.setStatus(result.getStatus());
        if ("CERTIFIED".equals(result.getStatus())) {
            invoice.setFelUuid(result.getUuid());
            invoice.setFelSerie(result.getSerie());
            invoice.setFelNumero(result.getNumero());
            invoice.setFelError(null);
            invoice.setFelCertifiedAt(LocalDateTime.now());
            if (result.getCertifiedXml() != null && !result.getCertifiedXml().isBlank()) {
                invoice.setFelCertifiedXml(result.getCertifiedXml());
            }
        } else if ("SKIPPED".equals(result.getStatus())) {
            invoice.setFelError(null);
        } else {
            invoice.setFelError(result.getErrorMessage());
        }
    }

    private void applyFailure(TaxInvoiceEntity invoice, String message) {
        invoice.setStatus("FAILED");
        invoice.setFelError(message);
    }

    private void enrichEmitterFromKioskLocation(TaxInvoiceDocument document, Long kioskLocationId) {
        if (document == null || kioskLocationId == null) {
            return;
        }
        locationRepository.findById(kioskLocationId).ifPresent(location -> applyLocationEmitter(document, location));
    }

    /**
     * Ventas en línea y emisión manual usan CUEROGLAM establecimiento 1 (bodega central),
     * salvo override explícito en el documento.
     */
    private void enrichEmitterForCueroGlamCentral(TaxInvoiceDocument document) {
        if (document == null) {
            return;
        }
        if (isBlank(document.getEmitterEstablishmentCode())) {
            document.setEmitterEstablishmentCode(properties.getCodigoEstablecimiento());
        }
        if (isBlank(document.getEmitterAddressLine())) {
            document.setEmitterAddressLine(properties.getDireccion());
        }
        if (isBlank(document.getEmitterMunicipio())) {
            document.setEmitterMunicipio(properties.getMunicipio());
        }
        if (isBlank(document.getEmitterDepartamento())) {
            document.setEmitterDepartamento(properties.getDepartamento());
        }
    }

    private void applyLocationEmitter(TaxInvoiceDocument document, LocationEntity location) {
        if (location.getFelEstablishmentCode() != null && !location.getFelEstablishmentCode().isBlank()) {
            document.setEmitterEstablishmentCode(location.getFelEstablishmentCode().trim());
        }
        if (location.getFelAddressLine() != null && !location.getFelAddressLine().isBlank()) {
            document.setEmitterAddressLine(location.getFelAddressLine().trim());
        }
        String municipio = firstNonBlank(location.getFelMunicipio(), location.getMunicipio());
        if (!municipio.isBlank()) {
            document.setEmitterMunicipio(municipio);
        }
        String departamento = firstNonBlank(location.getFelDepartamento(), location.getDepartamento());
        if (!departamento.isBlank()) {
            document.setEmitterDepartamento(departamento.toUpperCase(Locale.ROOT));
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private void validateDocument(TaxInvoiceDocument document) throws BusinessException {
        if (document.getLines() == null || document.getLines().isEmpty()) {
            throw new BusinessException("La factura debe tener al menos una línea.");
        }
        if (document.getTotalAmount() == null || document.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El total de la factura debe ser mayor a cero.");
        }
    }

    private void validateEmitterConfig() throws BusinessException {
        if (isBlank(properties.getNitEmisor())
                || isBlank(properties.getNombreEmisor())
                || isBlank(properties.getDireccion())) {
            throw new BusinessException(
                    "Configuración FEL incompleta (nit-emisor, nombre-emisor, dirección). Revise fel.emission.*");
        }
    }

    private TaxInvoiceDocument toDocument(TaxInvoiceEntity invoice) {
        List<TaxInvoiceDocument.Line> lines = invoice.getLines() == null
                ? List.of()
                : invoice.getLines().stream()
                .map(line -> TaxInvoiceDocument.Line.builder()
                        .description(line.getDescription())
                        .quantity(line.getQuantity())
                        .unitPrice(line.getUnitPrice())
                        .lineTotal(line.getLineTotal())
                        .build())
                .collect(Collectors.toList());

        return TaxInvoiceDocument.builder()
                .transactionId(invoice.getFelTransactionId())
                .issuedAt(invoice.getIssuedAt())
                .customerTaxId(invoice.getCustomerTaxId())
                .customerName(invoice.getCustomerName())
                .address(invoice.getAddress())
                .phone(invoice.getPhone())
                .email(invoice.getEmail())
                .subtotal(invoice.getSubtotal())
                .discountAmount(invoice.getDiscountAmount())
                .totalAmount(invoice.getTotalAmount())
                .lines(lines)
                .build();
    }

    private BigDecimal sumTax(TaxInvoiceDocument document) {
        BigDecimal total = BigDecimal.ZERO;
        for (TaxInvoiceDocument.Line line : document.getLines()) {
            FelIvaCalculator.IvaBreakdown iva = FelIvaCalculator.fromTaxIncludedTotal(nz(line.getLineTotal()));
            total = total.add(iva.tax());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public TaxInvoiceResponse toResponse(TaxInvoiceEntity invoice) {
        List<TaxInvoiceResponse.LineResponse> lines = invoice.getLines() == null
                ? List.of()
                : invoice.getLines().stream()
                .map(line -> TaxInvoiceResponse.LineResponse.builder()
                        .id(line.getId())
                        .lineNumber(line.getLineNumber())
                        .description(line.getDescription())
                        .quantity(line.getQuantity())
                        .unitPrice(line.getUnitPrice())
                        .lineTotal(line.getLineTotal())
                        .gravableAmount(line.getGravableAmount())
                        .taxAmount(line.getTaxAmount())
                        .build())
                .collect(Collectors.toList());

        return TaxInvoiceResponse.builder()
                .id(invoice.getId())
                .sourceType(invoice.getSourceType())
                .sourceId(invoice.getSourceId())
                .documentType(invoice.getDocumentType())
                .status(invoice.getStatus())
                .internalNumber(invoice.getInternalNumber())
                .issuedAt(invoice.getIssuedAt())
                .customerTaxId(invoice.getCustomerTaxId())
                .customerName(invoice.getCustomerName())
                .address(invoice.getAddress())
                .phone(invoice.getPhone())
                .email(invoice.getEmail())
                .subtotal(invoice.getSubtotal())
                .discountAmount(invoice.getDiscountAmount())
                .taxAmount(invoice.getTaxAmount())
                .totalAmount(invoice.getTotalAmount())
                .felUuid(invoice.getFelUuid())
                .felSerie(invoice.getFelSerie())
                .felNumero(invoice.getFelNumero())
                .felError(invoice.getFelError())
                .felCertifiedAt(invoice.getFelCertifiedAt())
                .hasCertifiedXml(hasCertifiedXml(invoice))
                .notes(invoice.getNotes())
                .createdAt(invoice.getCreatedAt())
                .createdBy(invoice.getCreatedBy())
                .lines(lines)
                .build();
    }

    private static String normalizeTaxId(String taxId) {
        return KioskSaleInvoiceMapper.normalizeTaxId(taxId);
    }

    private static String resolveCustomerName(String taxId, String customerName) {
        if ("CF".equals(taxId)) {
            return "CONSUMIDOR FINAL";
        }
        String name = customerName == null ? "" : customerName.trim();
        return name.isBlank() ? "CONSUMIDOR FINAL" : name;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean hasCertifiedXml(TaxInvoiceEntity invoice) {
        return invoice.getFelCertifiedXml() != null && !invoice.getFelCertifiedXml().isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String buildCertifiedXmlFilename(TaxInvoiceEntity invoice) {
        String serie = invoice.getFelSerie() != null ? invoice.getFelSerie().replaceAll("[^A-Za-z0-9_-]", "") : "S";
        String numero = invoice.getFelNumero() != null ? invoice.getFelNumero().replaceAll("[^A-Za-z0-9_-]", "") : String.valueOf(invoice.getId());
        return "FACT-" + serie + "-" + numero + ".xml";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
