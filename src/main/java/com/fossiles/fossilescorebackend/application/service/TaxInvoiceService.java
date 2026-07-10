package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ManualTaxInvoiceRequest;
import com.fossiles.fossilescorebackend.application.dto.request.UpdateTaxInvoiceFelMetadataRequest;
import com.fossiles.fossilescorebackend.application.dto.response.FelCertificationResult;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceAttemptResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceCertifiedXmlDownload;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceBackfillResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxInvoiceSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxpayerLookupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.model.TaxInvoiceDocument;
import com.fossiles.fossilescorebackend.infrastructure.config.FelCredentials;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationInternalNumberSequenceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationInternalNumberSequenceRepository;
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
import com.fossiles.fossilescorebackend.infrastructure.util.FelEmissionDateResolver;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
    private static final Set<String> VALID_DOCUMENT_TYPES = Set.of("FACT", "FCAM");

    private final TaxInvoiceRepository taxInvoiceRepository;
    private final KioskSaleRepository kioskSaleRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final LocationRepository locationRepository;
    private final LocationInternalNumberSequenceRepository locationInternalNumberSequenceRepository;
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
    private final TaxInvoiceAccessGuard taxInvoiceAccessGuard;

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
                    || "SKIPPED".equals(invoice.getStatus())
                    || isVoidReadyForReissue(invoice)) {
                if (isVoidReadyForReissue(invoice)) {
                    invoice.setStatus("DRAFT");
                    taxInvoiceRepository.save(invoice);
                }
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
        applyDocumentTypeByEstablishment(document);
        validateDocument(document);
        TaxInvoiceEntity invoice = persistDraft("KIOSK_SALE", sale.getId(), document, sale.getCreatedBy(), sale.getKioskLocationId());
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
            throw new BusinessException("No se pudo generar la factura electrónica para esta venta POS.");
        }
        return response;
    }

    /**
     * Crea borradores tax_invoice para ventas POS que aún no tienen registro asociado.
     * No certifica ante FEL: sirve para cargar después UUID/serie/número manualmente.
     */
    @Transactional
    public TaxInvoiceBackfillResponse backfillMissingKioskSaleDrafts(
            Long kioskLocationId,
            LocalDate fromDate,
            LocalDate toDate,
            boolean dryRun
    ) throws BusinessException {
        taxInvoiceAccessGuard.assertCanEditFelMetadata();

        List<KioskSaleEntity> candidates = kioskSaleRepository.findMissingTaxInvoice(
                kioskLocationId,
                fromDate,
                toDate
        );

        int created = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        List<TaxInvoiceBackfillResponse.Item> samples = new ArrayList<>();

        for (KioskSaleEntity sale : candidates) {
            if (dryRun) {
                appendBackfillSample(samples, sale, null, "Pendiente de crear borrador");
                continue;
            }
            try {
                TaxInvoiceResponse invoice = createDraftFromKioskSale(sale);
                created++;
                appendBackfillSample(
                        samples,
                        sale,
                        invoice,
                        "Borrador creado"
                );
            } catch (BusinessException ex) {
                failed++;
                if (errors.size() < 100) {
                    errors.add("Venta " + safeSaleLabel(sale) + ": " + ex.getMessage());
                }
                appendBackfillSample(samples, sale, null, ex.getMessage());
            } catch (Exception ex) {
                failed++;
                log.warn("Backfill tax_invoice falló para venta {}: {}", sale.getId(), ex.getMessage(), ex);
                if (errors.size() < 100) {
                    errors.add("Venta " + safeSaleLabel(sale) + ": " + ex.getMessage());
                }
                appendBackfillSample(samples, sale, null, ex.getMessage());
            }
        }

        return TaxInvoiceBackfillResponse.builder()
                .dryRun(dryRun)
                .candidates(candidates.size())
                .created(created)
                .skipped(skipped)
                .failed(failed)
                .errors(errors)
                .samples(samples.stream().limit(50).collect(Collectors.toList()))
                .build();
    }

    /**
     * Crea (o reutiliza) el borrador tax_invoice de una venta POS sin certificar FEL.
     */
    @Transactional
    public TaxInvoiceResponse createDraftFromKioskSaleId(Long saleId)
            throws BusinessException, ResourceNotFoundException {
        taxInvoiceAccessGuard.assertCanEditFelMetadata();
        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        return createDraftFromKioskSale(sale);
    }

    private TaxInvoiceResponse createDraftFromKioskSale(KioskSaleEntity sale) throws BusinessException {
        Optional<TaxInvoiceEntity> existing = taxInvoiceRepository.findBySourceTypeAndSourceId("KIOSK_SALE", sale.getId());
        if (existing.isPresent()) {
            TaxInvoiceEntity invoice = existing.get();
            linkKioskSaleToInvoice(sale, invoice);
            return toResponse(invoice);
        }

        TaxInvoiceDocument document = kioskSaleInvoiceMapper.fromSale(sale);
        enrichEmitterFromKioskLocation(document, sale.getKioskLocationId());
        applyDocumentTypeByEstablishment(document);
        validateDocument(document);

        TaxInvoiceEntity invoice = persistDraft("KIOSK_SALE", sale.getId(), document, sale.getCreatedBy(), sale.getKioskLocationId());
        applyKioskSaleFelSnapshot(invoice, sale);
        invoice.setFelTransactionId(document.getTransactionId());
        appendInvoiceNote(invoice, "[Backfill] Borrador generado desde venta POS existente.");
        TaxInvoiceEntity saved = taxInvoiceRepository.save(invoice);
        linkKioskSaleToInvoice(sale, saved);
        return toResponse(saved);
    }

    private void linkKioskSaleToInvoice(KioskSaleEntity sale, TaxInvoiceEntity invoice) {
        if (sale == null || invoice == null || invoice.getId() == null) {
            return;
        }
        if (!Objects.equals(sale.getInvoiceId(), invoice.getId())) {
            sale.setInvoiceId(invoice.getId());
        }
        syncKioskSaleFelFields(sale, invoice);
        kioskSaleRepository.save(sale);
    }

    private void applyKioskSaleFelSnapshot(TaxInvoiceEntity invoice, KioskSaleEntity sale) {
        if (invoice == null || sale == null) {
            return;
        }
        String status = trimToNull(sale.getFelStatus());
        if (status != null) {
            invoice.setStatus(status);
        }
        if (sale.getFelUuid() != null && !sale.getFelUuid().isBlank()) {
            invoice.setFelUuid(sale.getFelUuid().trim());
        }
        if (sale.getFelSerie() != null && !sale.getFelSerie().isBlank()) {
            invoice.setFelSerie(sale.getFelSerie().trim());
        }
        if (sale.getFelNumero() != null && !sale.getFelNumero().isBlank()) {
            invoice.setFelNumero(sale.getFelNumero().trim());
        }
        if (sale.getFelError() != null && !sale.getFelError().isBlank()) {
            invoice.setFelError(sale.getFelError().trim());
        }
        if (sale.getFelCertifiedAt() != null) {
            invoice.setFelCertifiedAt(sale.getFelCertifiedAt());
            invoice.setIssuedAt(sale.getFelCertifiedAt());
        } else if (sale.getSoldAt() != null) {
            invoice.setIssuedAt(sale.getSoldAt());
        }
    }

    private static String safeSaleLabel(KioskSaleEntity sale) {
        if (sale == null) {
            return "—";
        }
        if (sale.getSaleNumber() != null && !sale.getSaleNumber().isBlank()) {
            return sale.getSaleNumber().trim() + " (id=" + sale.getId() + ")";
        }
        return "id=" + sale.getId();
    }

    private static void appendBackfillSample(
            List<TaxInvoiceBackfillResponse.Item> samples,
            KioskSaleEntity sale,
            TaxInvoiceResponse invoice,
            String message
    ) {
        if (samples.size() >= 50 || sale == null) {
            return;
        }
        samples.add(TaxInvoiceBackfillResponse.Item.builder()
                .saleId(sale.getId())
                .saleNumber(sale.getSaleNumber())
                .invoiceId(invoice != null ? invoice.getId() : null)
                .internalNumber(invoice != null ? invoice.getInternalNumber() : null)
                .status(invoice != null ? invoice.getStatus() : null)
                .message(message)
                .build());
    }

    @Transactional
    public TaxInvoiceResponse voidInvoice(Long invoiceId, String reason) throws BusinessException, ResourceNotFoundException {
        taxInvoiceAccessGuard.assertCanEditFelMetadata();
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("Debes indicar el motivo de anulación.");
        }
        TaxInvoiceEntity invoice = taxInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxInvoice", invoiceId));
        if ("VOID".equalsIgnoreCase(safe(invoice.getStatus()))) {
            throw new BusinessException("La factura ya está anulada localmente. Si necesita reemitir, use Firmar FEL.");
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

        FelCredentials credentials = properties.resolveCredentials(resolveSandboxMode(invoice));
        validateEmitterConfig(credentials);
        String originalEmission = FelEmissionDateResolver.resolveAnnulmentEmissionDateTime(invoice);

        String transactionId = "VOID-" + invoice.getId() + "-" + System.currentTimeMillis();
        String unsignedXml = anulacionXmlBuilder.buildUnsignedAnulacionXml(
                invoice.getFelUuid(),
                credentials.nitEmisor(),
                invoice.getCustomerTaxId(),
                originalEmission,
                trimmedReason
        );
        String signedXml = signerService.signXml(unsignedXml, transactionId, true, credentials);
        FelCertificationResult result = certificationService.certifyAnnulmentSignedXml(signedXml, transactionId, credentials);

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

    /**
     * Tras anular ante el SAT, deja la factura en borrador para volver a certificar.
     * Limpia UUID/serie/número FEL en tax_invoice y kiosk_sale; conserva fel_void_uuid y motivo.
     */
    private void markInvoiceVoidLocal(TaxInvoiceEntity invoice, String reason, String voidUuid) {
        invoice.setStatus("DRAFT");
        clearFelCertificationFields(invoice);
        invoice.setVoidedAt(GuatemalaDateTime.now());
        invoice.setVoidReason(reason);
        invoice.setFelVoidUuid(voidUuid);
    }

    private void clearFelCertificationFields(TaxInvoiceEntity invoice) {
        invoice.setFelUuid(null);
        invoice.setFelSerie(null);
        invoice.setFelNumero(null);
        invoice.setFelError(null);
        invoice.setFelCertifiedAt(null);
        invoice.setFelCertifiedXml(null);
    }

    private void clearKioskSaleFelFields(KioskSaleEntity sale) {
        sale.setFelStatus("DRAFT");
        sale.setFelUuid(null);
        sale.setFelSerie(null);
        sale.setFelNumero(null);
        sale.setFelError(null);
        sale.setFelCertifiedAt(null);
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
                    || "SKIPPED".equals(invoice.getStatus())
                    || isVoidReadyForReissue(invoice)) {
                if (isVoidReadyForReissue(invoice)) {
                    invoice.setStatus("DRAFT");
                    taxInvoiceRepository.save(invoice);
                }
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
        applyDocumentTypeByEstablishment(document);
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
        Long kioskSaleId = resolveManualKioskSaleId(request);
        if (kioskSaleId != null) {
            return issueManualFromKioskSale(request, kioskSaleId);
        }

        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new BusinessException("Debe incluir al menos una línea.");
        }

        TaxInvoiceDocument document = buildManualDocument(request);
        if (!locationRepository.existsById(request.getLocationId())) {
            throw new BusinessException("La ubicación seleccionada no existe.");
        }
        enrichEmitterFromKioskLocation(document, request.getLocationId());
        applyDocumentTypeByEstablishment(document);
        assertEmitterConfigured(document);
        assertInternalSeriesConfigured(request.getLocationId(), document);
        enrichReceptorFromLookup(document);
        validateDocument(document);
        Long userId = securityUtil.getCurrentUserId();
        TaxInvoiceEntity invoice = persistDraft("MANUAL", null, document, userId, request.getLocationId());
        invoice.setNotes(trimToNull(request.getNotes()));
        taxInvoiceRepository.save(invoice);
        certify(invoice, document, false);
        return toResponse(invoice);
    }

    private Long resolveManualKioskSaleId(ManualTaxInvoiceRequest request) throws BusinessException {
        if (request.getKioskSaleId() != null) {
            return request.getKioskSaleId();
        }
        String saleNumber = trimToNull(request.getKioskSaleNumber());
        if (saleNumber == null) {
            return null;
        }
        if (request.getLocationId() == null) {
            throw new BusinessException("Seleccione el establecimiento para buscar la venta POS.");
        }
        return kioskSaleRepository.findByKioskLocationIdAndSaleNumberIgnoreCase(
                        request.getLocationId(),
                        saleNumber
                )
                .map(KioskSaleEntity::getId)
                .orElseThrow(() -> new BusinessException(
                        "No se encontró la venta POS " + saleNumber + " en el establecimiento seleccionado."));
    }

    private TaxInvoiceResponse issueManualFromKioskSale(
            ManualTaxInvoiceRequest request,
            Long kioskSaleId
    ) throws BusinessException {
        KioskSaleEntity sale = kioskSaleRepository.findById(kioskSaleId)
                .orElseThrow(() -> new BusinessException("La venta POS seleccionada no existe."));
        if (!Objects.equals(sale.getKioskLocationId(), request.getLocationId())) {
            throw new BusinessException("La venta POS no pertenece al establecimiento seleccionado.");
        }
        if ("VOID".equalsIgnoreCase(safe(sale.getStatus()))) {
            throw new BusinessException("No se puede facturar una venta anulada.");
        }

        Optional<TaxInvoiceEntity> existing = resolveKioskSaleInvoice(sale);
        if (existing.isPresent() && !isEligibleForKioskSaleFelAssociation(existing.get())) {
            throw new BusinessException("La venta POS ya tiene una factura certificada o no disponible para asociar.");
        }

        TaxInvoiceDocument document = kioskSaleInvoiceMapper.fromSale(sale);
        enrichEmitterFromKioskLocation(document, request.getLocationId());
        applyDocumentTypeByEstablishment(document);
        assertEmitterConfigured(document);
        assertInternalSeriesConfigured(request.getLocationId(), document);
        enrichReceptorFromLookup(document);
        validateDocument(document);

        if (existing.isPresent()) {
            TaxInvoiceEntity invoice = existing.get();
            assignInternalNumber(invoice, document, request.getLocationId());
            invoice.setSourceType("KIOSK_SALE");
            invoice.setSourceId(sale.getId());
            syncInvoiceFromDocument(invoice, document);
            invoice.setFelTransactionId(document.getTransactionId());
            if (request.getNotes() != null) {
                appendInvoiceNote(invoice, trimToNull(request.getNotes()));
            }
            taxInvoiceRepository.save(invoice);
            linkKioskSaleToInvoice(sale, invoice);
            certify(invoice, document, true);
            syncSourceFelFields(invoice);
            return toResponse(invoice);
        }

        Long userId = securityUtil.getCurrentUserId();
        TaxInvoiceEntity invoice = persistDraft("KIOSK_SALE", sale.getId(), document, userId, request.getLocationId());
        if (request.getNotes() != null) {
            invoice.setNotes(trimToNull(request.getNotes()));
            taxInvoiceRepository.save(invoice);
        }
        linkKioskSaleToInvoice(sale, invoice);
        certify(invoice, document, false);
        return toResponse(invoice);
    }

    private Optional<TaxInvoiceEntity> resolveKioskSaleInvoice(KioskSaleEntity sale) {
        if (sale == null) {
            return Optional.empty();
        }
        Optional<TaxInvoiceEntity> bySource = taxInvoiceRepository.findBySourceTypeAndSourceId(
                "KIOSK_SALE",
                sale.getId()
        );
        if (bySource.isPresent()) {
            return bySource;
        }
        if (sale.getInvoiceId() != null) {
            return taxInvoiceRepository.findById(sale.getInvoiceId());
        }
        if (sale.getSaleNumber() != null && !sale.getSaleNumber().isBlank()) {
            return taxInvoiceRepository.findFirstByFelTransactionIdIgnoreCase(sale.getSaleNumber().trim());
        }
        return Optional.empty();
    }

    private boolean isEligibleForKioskSaleFelAssociation(TaxInvoiceEntity invoice) {
        if (invoice == null) {
            return true;
        }
        if ("CERTIFIED".equals(invoice.getStatus()) || ("VOID".equalsIgnoreCase(safe(invoice.getStatus()))
                && !isVoidReadyForReissue(invoice))) {
            return false;
        }
        boolean hasFelUuid = invoice.getFelUuid() != null && !invoice.getFelUuid().isBlank();
        return !hasFelUuid;
    }

    @Transactional
    public TaxInvoiceResponse retry(Long invoiceId) throws BusinessException, ResourceNotFoundException {
        TaxInvoiceEntity invoice = taxInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxInvoice", invoiceId));
        if ("CERTIFIED".equals(invoice.getStatus())) {
            throw new BusinessException("La factura ya está certificada.");
        }
        if ("VOID".equalsIgnoreCase(safe(invoice.getStatus()))) {
            if (invoice.getFelVoidUuid() != null && !invoice.getFelVoidUuid().isBlank()) {
                clearFelCertificationFields(invoice);
                invoice.setStatus("DRAFT");
                taxInvoiceRepository.save(invoice);
                syncSourceFelFields(invoice);
            } else if (invoice.getFelUuid() != null && !invoice.getFelUuid().isBlank()) {
                throw new BusinessException(
                        "La factura quedó marcada como anulada. Anúlela de nuevo desde Facturas FEL para dejarla en borrador.");
            } else {
                invoice.setStatus("DRAFT");
                taxInvoiceRepository.save(invoice);
            }
        }
        TaxInvoiceDocument document = rebuildDocumentForRetry(invoice);
        Long locationId = resolveLocationIdForInvoice(invoice);
        assignInternalNumber(invoice, document, locationId);
        syncInvoiceFromDocument(invoice, document);
        certify(invoice, document, true);
        syncSourceFelFields(invoice);
        return getById(invoiceId);
    }

    /**
     * Corrige manualmente UUID, serie, número y fecha de emisión FEL cuando la venta quedó en prueba
     * pero la factura real se emitió en SAT. Solo admin, logística y contabilidad.
     */
    @Transactional
    public TaxInvoiceResponse updateFelMetadata(Long invoiceId, UpdateTaxInvoiceFelMetadataRequest request)
            throws BusinessException, ResourceNotFoundException {
        taxInvoiceAccessGuard.assertCanEditFelMetadata();
        TaxInvoiceEntity invoice = taxInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxInvoice", invoiceId));
        if ("VOID".equalsIgnoreCase(safe(invoice.getStatus()))) {
            throw new BusinessException("No se puede editar una factura anulada.");
        }

        String uuid = trimToNull(request.getFelUuid());
        String serie = trimToNull(request.getFelSerie());
        String numero = trimToNull(request.getFelNumero());
        if (uuid == null || serie == null || numero == null) {
            throw new BusinessException("UUID, serie y número FEL son obligatorios.");
        }
        if (request.getFelCertifiedDate() == null) {
            throw new BusinessException("La fecha de emisión FEL es obligatoria.");
        }
        validateFelUuidFormat(uuid);

        LocalDateTime certifiedAt = request.getFelCertifiedDate().atTime(12, 0);
        String previousSummary = summarizeFel(invoice);

        invoice.setFelUuid(uuid);
        invoice.setFelSerie(serie);
        invoice.setFelNumero(numero);
        invoice.setFelCertifiedAt(certifiedAt);
        invoice.setIssuedAt(certifiedAt);
        invoice.setFelError(null);
        invoice.setStatus("CERTIFIED");

        String userNote = trimToNull(request.getCorrectionNotes());
        String auditLine = "[Corrección FEL manual "
                + GuatemalaDateTime.now()
                + " por usuario "
                + securityUtil.getCurrentUserId()
                + "] "
                + (userNote != null ? userNote : "Datos FEL actualizados")
                + (previousSummary != null ? " | Anterior: " + previousSummary : "");
        appendInvoiceNote(invoice, auditLine);

        TaxInvoiceEntity saved = taxInvoiceRepository.save(invoice);
        syncSourceFelFields(saved);
        return toResponse(saved);
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
            applyDocumentTypeByEstablishment(document);
            enrichReceptorFromLookup(document);
            return document;
        }
        if ("KIOSK_SALE".equals(invoice.getSourceType()) && invoice.getSourceId() != null) {
            KioskSaleEntity sale = kioskSaleRepository.findById(invoice.getSourceId())
                    .orElseThrow(() -> new ResourceNotFoundException("KioskSale", invoice.getSourceId()));
            TaxInvoiceDocument document = kioskSaleInvoiceMapper.fromSale(sale);
            enrichEmitterFromKioskLocation(document, sale.getKioskLocationId());
            applyDocumentTypeByEstablishment(document);
            return document;
        }
        TaxInvoiceDocument existing = toDocument(invoice);
        applyDocumentTypeByEstablishment(existing);
        return existing;
    }

    private void syncInvoiceFromDocument(TaxInvoiceEntity invoice, TaxInvoiceDocument document) {
        // El número de control interno ya quedó fijado al crear el borrador.
        // El tipo FACT/FCAM se recalcula por establecimiento (est. 1 = FCAM).
        document.setInternalNumber(invoice.getInternalNumber());
        applyDocumentTypeByEstablishment(document);
        invoice.setDocumentType(document.getDocumentType());
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
    public String getInternalNumber(Long invoiceId) {
        if (invoiceId == null) {
            return null;
        }
        return taxInvoiceRepository.findById(invoiceId)
                .map(TaxInvoiceEntity::getInternalNumber)
                .orElse(null);
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
            String internalNumber,
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
        String internalNumberPattern = buildInternalNumberPattern(internalNumber);
        return taxInvoiceRepository.search(
                        normalizedSourceType,
                        normalizedStatus,
                        customerTaxIdPattern,
                        internalNumberPattern,
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

    private String buildInternalNumberPattern(String internalNumber) {
        String normalized = trimToNull(internalNumber);
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
                .issuedAt(GuatemalaDateTime.now())
                .documentType(resolveDocumentType(request.getDocumentType()))
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
            Long createdBy,
            Long locationIdForSeries
    ) {
        BigDecimal taxAmount = sumTax(document);
        applyDocumentTypeByEstablishment(document);
        String documentType = resolveDocumentType(document.getDocumentType());
        document.setDocumentType(documentType);
        TaxInvoiceEntity invoice = TaxInvoiceEntity.builder()
                .sourceType(sourceType)
                .sourceId(sourceId)
                .documentType(documentType)
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
                .issuedAt(document.getIssuedAt() != null ? document.getIssuedAt() : GuatemalaDateTime.now())
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
        assignInternalNumber(saved, document, locationIdForSeries);
        return taxInvoiceRepository.save(saved);
    }

    private TaxInvoiceEntity persistDraft(
            String sourceType,
            Long sourceId,
            TaxInvoiceDocument document,
            Long createdBy
    ) {
        return persistDraft(sourceType, sourceId, document, createdBy, null);
    }

    private Long resolveLocationIdForInvoice(TaxInvoiceEntity invoice) {
        if (invoice == null || !"KIOSK_SALE".equals(invoice.getSourceType()) || invoice.getSourceId() == null) {
            return null;
        }
        return kioskSaleRepository.findById(invoice.getSourceId())
                .map(KioskSaleEntity::getKioskLocationId)
                .orElse(null);
    }

    private String resolveLocationSeriesCode(Long locationId, TaxInvoiceDocument document) {
        if (locationId != null) {
            String fromLocation = locationRepository.findById(locationId)
                    .map(LocationEntity::getInternalSeriesCode)
                    .filter(code -> code != null && !code.isBlank())
                    .map(code -> code.trim().toUpperCase(Locale.ROOT))
                    .orElse(null);
            if (fromLocation != null) {
                return fromLocation;
            }
        }
        if (document != null
                && document.getLocationInternalSeriesCode() != null
                && !document.getLocationInternalSeriesCode().isBlank()) {
            return document.getLocationInternalSeriesCode().trim().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean matchesInternalSeriesFormat(String internalNumber, String seriesCode) {
        if (seriesCode == null || seriesCode.isBlank() || internalNumber == null || internalNumber.isBlank()) {
            return false;
        }
        return internalNumber.trim().toUpperCase(Locale.ROOT)
                .matches("^" + java.util.regex.Pattern.quote(seriesCode.trim().toUpperCase(Locale.ROOT)) + "-\\d+$");
    }

    private static boolean needsInternalNumberAssignment(String internalNumber, String seriesCode) {
        if (internalNumber == null || internalNumber.isBlank()) {
            return true;
        }
        String normalized = internalNumber.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("TINV-")) {
            return true;
        }
        if (seriesCode == null || seriesCode.isBlank()) {
            return false;
        }
        return !matchesInternalSeriesFormat(normalized, seriesCode);
    }

    /**
     * Asigna número interno "{serie}-{correlativo}" desde location_internal_number_sequence.
     * Reutiliza el existente si ya tiene la serie correcta (reintentos / borradores backfill).
     */
    private void assignInternalNumber(
            TaxInvoiceEntity invoice,
            TaxInvoiceDocument document,
            Long locationId
    ) {
        if (invoice == null || document == null || invoice.getId() == null) {
            return;
        }
        String seriesCode = resolveLocationSeriesCode(locationId, document);
        if (seriesCode != null && !seriesCode.isBlank()) {
            document.setLocationInternalSeriesCode(seriesCode);
        }
        if (!needsInternalNumberAssignment(invoice.getInternalNumber(), seriesCode)) {
            document.setInternalNumber(invoice.getInternalNumber());
            return;
        }
        if (seriesCode == null || seriesCode.isBlank()) {
            String fallback = String.format("TINV-%06d", invoice.getId());
            invoice.setInternalNumber(fallback);
            document.setInternalNumber(fallback);
            return;
        }
        String internalNumber = generateInternalNumber(invoice.getId(), seriesCode);
        invoice.setInternalNumber(internalNumber);
        document.setInternalNumber(internalNumber);
    }

    private String resolveDocumentType(String requested) {
        String normalized = trimToNull(requested);
        if (normalized == null) {
            return "FACT";
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        return VALID_DOCUMENT_TYPES.contains(normalized) ? normalized : "FACT";
    }

    /**
     * Número de control interno = "{código de serie de la ubicación}-{correlativo}", ej. "A1-241".
     * El correlativo avanza por serie solo cuando se emite una factura (nunca por cada venta).
     * Si la ubicación aún no tiene código de serie asignado, se usa un identificador genérico
     * basado en el id de la factura para no bloquear la emisión.
     */
    private String generateInternalNumber(Long invoiceId, String locationSeriesCode) {
        if (locationSeriesCode == null || locationSeriesCode.isBlank()) {
            return String.format("TINV-%06d", invoiceId);
        }
        LocationInternalNumberSequenceEntity sequence = locationInternalNumberSequenceRepository
                .findWithLockBySeriesCode(locationSeriesCode)
                .orElseGet(() -> LocationInternalNumberSequenceEntity.builder()
                        .seriesCode(locationSeriesCode)
                        .lastNumber(0)
                        .build());
        int next = (sequence.getLastNumber() != null ? sequence.getLastNumber() : 0) + 1;
        sequence.setLastNumber(next);
        locationInternalNumberSequenceRepository.save(sequence);
        return locationSeriesCode + "-" + next;
    }

    private static boolean requiresNewFelTransactionAfterVoid(TaxInvoiceEntity invoice) {
        return invoice != null
                && invoice.getFelVoidUuid() != null
                && !invoice.getFelVoidUuid().isBlank()
                && (invoice.getFelUuid() == null || invoice.getFelUuid().isBlank());
    }

    private String resolveCertificationTransactionId(
            TaxInvoiceEntity invoice,
            TaxInvoiceDocument document,
            boolean retry,
            boolean reissueAfterVoid
    ) {
        if (reissueAfterVoid) {
            return buildReplacementFelTransactionId(invoice, document);
        }
        if (retry && invoice.getFelTransactionId() != null && !invoice.getFelTransactionId().isBlank()) {
            return invoice.getFelTransactionId();
        }
        String fromDocument = document != null ? trimToNull(document.getTransactionId()) : null;
        if (fromDocument != null) {
            return fromDocument;
        }
        String fromInvoice = trimToNull(invoice.getFelTransactionId());
        if (fromInvoice != null) {
            return fromInvoice;
        }
        return "TINV-" + invoice.getId();
    }

    private String buildReplacementFelTransactionId(TaxInvoiceEntity invoice, TaxInvoiceDocument document) {
        String base = trimToNull(invoice.getFelTransactionId());
        if (base == null && document != null) {
            base = trimToNull(document.getTransactionId());
        }
        if (base == null) {
            base = "TINV-" + invoice.getId();
        }
        int reissueSuffix = base.indexOf("-R");
        if (reissueSuffix > 0) {
            base = base.substring(0, reissueSuffix);
        }
        return base + "-R" + System.currentTimeMillis();
    }

    private void certify(TaxInvoiceEntity invoice, TaxInvoiceDocument document, boolean retry)
            throws BusinessException {
        boolean reissueAfterVoid = requiresNewFelTransactionAfterVoid(invoice);
        String action = reissueAfterVoid ? "REISSUE" : (retry ? "RETRY" : "ISSUE");
        if (!properties.isEnabled()) {
            applyResult(invoice, FelCertificationResult.builder().status("SKIPPED").build());
            taxInvoiceRepository.save(invoice);
            recordCertificationAttempt(invoice, document, action);
            return;
        }

        String transactionId = resolveCertificationTransactionId(invoice, document, retry, reissueAfterVoid);
        invoice.setFelTransactionId(transactionId);
        document.setTransactionId(transactionId);

        try {
            FelCredentials credentials = properties.resolveCredentials(resolveSandboxMode(invoice));
            validateEmitterConfig(credentials);
            if (invoice.getInternalNumber() != null && !invoice.getInternalNumber().isBlank()) {
                document.setInternalNumber(invoice.getInternalNumber());
            }
            applyDocumentTypeByEstablishment(document);
            String unsignedXml = factXmlBuilder.buildUnsignedXml(document, credentials);
            String signedXml = signerService.signXml(unsignedXml, transactionId, credentials);
            FelCertificationResult result = certificationService.certifySignedXml(signedXml, transactionId, credentials);
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
            return;
        }
        if ("ONLINE_SALE".equals(invoice.getSourceType()) && invoice.getSourceId() != null) {
            onlineSaleRepository.findById(invoice.getSourceId()).ifPresent(sale -> {
                if (sale.getInvoiceId() == null) {
                    sale.setInvoiceId(invoice.getId());
                    onlineSaleRepository.save(sale);
                }
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
        } else if ("DRAFT".equals(invoice.getStatus())) {
            clearKioskSaleFelFields(sale);
        } else if ("VOID".equals(invoice.getStatus())) {
            sale.setFelError(invoice.getVoidReason());
        } else if ("SKIPPED".equals(invoice.getStatus())) {
            sale.setFelError(null);
        } else {
            sale.setFelError(invoice.getFelError());
        }
    }

    private static boolean isVoidReadyForReissue(TaxInvoiceEntity invoice) {
        return invoice != null
                && "VOID".equalsIgnoreCase(safe(invoice.getStatus()))
                && (invoice.getFelUuid() == null || invoice.getFelUuid().isBlank());
    }

    private void applyResult(TaxInvoiceEntity invoice, FelCertificationResult result) {
        invoice.setStatus(result.getStatus());
        if ("CERTIFIED".equals(result.getStatus())) {
            invoice.setFelUuid(result.getUuid());
            invoice.setFelSerie(result.getSerie());
            invoice.setFelNumero(result.getNumero());
            invoice.setFelError(null);
            invoice.setFelCertifiedAt(GuatemalaDateTime.now());
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

    private void assertEmitterConfigured(TaxInvoiceDocument document) throws BusinessException {
        if (document == null || isBlank(document.getEmitterEstablishmentCode())) {
            throw new BusinessException(
                    "La ubicación seleccionada no tiene código de establecimiento FEL configurado.");
        }
    }

    private void assertInternalSeriesConfigured(Long locationId, TaxInvoiceDocument document) throws BusinessException {
        if (resolveLocationSeriesCode(locationId, document) == null) {
            throw new BusinessException(
                    "El establecimiento seleccionado no tiene serie de número interno configurada (ej. A45). "
                            + "Revise Catálogos → Ubicaciones y location_internal_number_sequence.");
        }
    }

    /**
     * Ventas en línea usan CUEROGLAM establecimiento FEL 1 (bodega central) → FCAM.
     * Siempre fija código "1" (no el default de properties de otros flujos).
     */
    private void enrichEmitterForCueroGlamCentral(TaxInvoiceDocument document) {
        if (document == null) {
            return;
        }
        final String centralCode = "1";
        locationRepository.findFirstByFelEstablishmentCode(centralCode)
                .ifPresent(location -> applyLocationEmitter(document, location));
        document.setEmitterEstablishmentCode(centralCode);
        if (isBlank(document.getEmitterCommercialName())) {
            document.setEmitterCommercialName(properties.getNombreComercial());
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
        if (location.getFelEstablishmentName() != null && !location.getFelEstablishmentName().isBlank()) {
            document.setEmitterCommercialName(location.getFelEstablishmentName().trim());
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
        if (location.getInternalSeriesCode() != null && !location.getInternalSeriesCode().isBlank()) {
            document.setLocationInternalSeriesCode(location.getInternalSeriesCode().trim().toUpperCase(Locale.ROOT));
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private void applyDocumentTypeByEstablishment(TaxInvoiceDocument document) {
        if (document == null) {
            return;
        }
        String establishmentCode = firstNonBlank(
                document.getEmitterEstablishmentCode(),
                properties.getCodigoEstablecimiento());
        document.setDocumentType(
                FelFactXmlBuilder.resolveEmissionDocumentType(document.getDocumentType(), establishmentCode));
    }

    private void validateDocument(TaxInvoiceDocument document) throws BusinessException {
        if (document.getLines() == null || document.getLines().isEmpty()) {
            throw new BusinessException("La factura debe tener al menos una línea.");
        }
        if (document.getTotalAmount() == null || document.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El total de la factura debe ser mayor a cero.");
        }
    }

    private void validateEmitterConfig(FelCredentials credentials) throws BusinessException {
        if (isBlank(credentials.nitEmisor())
                || isBlank(credentials.nombreEmisor())
                || isBlank(credentials.direccion())) {
            throw new BusinessException(
                    "Configuración FEL incompleta (nit-emisor, nombre-emisor, dirección). Revise fel.emission.*");
        }
    }

    /**
     * Decide si una factura debe certificarse contra el ambiente sandbox (implementación) o
     * producción de INFILE. El apagador global {@code fel.emission.test-mode} fuerza sandbox
     * para todo; si no está activo, para ventas de kiosko se respeta el flag por ubicación
     * (locations.pos_test_mode, ya reflejado en KioskSaleEntity.testSale). Ventas online y
     * facturas manuales usan siempre CUEROGLAM central, por lo que solo dependen del apagador global.
     */
    private boolean resolveSandboxMode(TaxInvoiceEntity invoice) {
        if (properties.isTestMode()) {
            return true;
        }
        if ("KIOSK_SALE".equals(invoice.getSourceType()) && invoice.getSourceId() != null) {
            return kioskSaleRepository.findById(invoice.getSourceId())
                    .map(sale -> Boolean.TRUE.equals(sale.getTestSale()))
                    .orElse(false);
        }
        return false;
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
                .documentType(invoice.getDocumentType())
                .internalNumber(invoice.getInternalNumber())
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
                .voidedAt(invoice.getVoidedAt())
                .voidReason(invoice.getVoidReason())
                .felVoidUuid(invoice.getFelVoidUuid())
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

    private static void validateFelUuidFormat(String uuid) throws BusinessException {
        String normalized = uuid.trim();
        if (normalized.length() < 30 || normalized.length() > 64) {
            throw new BusinessException("El UUID FEL no tiene un formato válido.");
        }
    }

    private static String summarizeFel(TaxInvoiceEntity invoice) {
        if (invoice == null) {
            return null;
        }
        String serie = trimToNull(invoice.getFelSerie());
        String numero = trimToNull(invoice.getFelNumero());
        String uuid = trimToNull(invoice.getFelUuid());
        if (serie == null && numero == null && uuid == null) {
            return null;
        }
        return (serie != null ? serie : "—") + "-" + (numero != null ? numero : "—")
                + (uuid != null ? " UUID=" + uuid : "");
    }

    private static void appendInvoiceNote(TaxInvoiceEntity invoice, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String existing = invoice.getNotes() == null ? "" : invoice.getNotes().trim();
        invoice.setNotes(existing.isEmpty() ? line.trim() : existing + "\n" + line.trim());
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
