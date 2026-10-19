package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationFileParser.ParsedReceipt;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationFileParser.RowError;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationMatcher.Hit;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/**
 * Fondos recibidos: importar archivos (idempotente), capturar a mano, asociarlos a casos y anular aplicaciones.
 * Un fondo solo se aplica solo si un identificador exacto (guía, referencia o ENVL) apunta a UN caso. Monto y
 * fecha parecidos solo generan candidatos para que una persona decida.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationReceiptService {

    private static final Pattern ENVL_PATTERN = Pattern.compile("(?i)^ENVL-?\\d+$");
    private static final int REPORT_LIMIT = 500;

    private final ReconReceiptRepository receiptRepository;
    private final ReconImportFileRepository importFileRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconCaseRepository caseRepository;
    private final OnlineSaleRepository saleRepository;
    private final ReconciliationRecomputeService recompute;
    private final ReconEventService events;
    private final ReconciliationProperties props;
    private final ReconActor actor;
    private final ObjectMapper objectMapper;

    // ───────── importación ─────────

    @Transactional
    public ImportResult importFile(byte[] bytes, String fileName, Recon.SourceType sourceType) throws BusinessException {
        if (sourceType == null) throw new BusinessException("Indique el tipo de fuente del archivo.");
        String sha = sha256(bytes);
        Optional<ReconImportFileEntity> already = importFileRepository.findBySha256(sha);
        if (already.isPresent()) {
            ReconImportFileEntity f = already.get();
            return new ImportResult(f.getId(), true, f.getRowsTotal(), 0, 0, 0, f.getRowsMatched(), 0, 0, 0,
                    List.of(new RowReport(0, "ARCHIVO_REPETIDO", "Este archivo ya se importó el " + f.getImportedAt().toLocalDate()
                            + ". No se volvió a procesar nada.")));
        }
        ReconciliationFileParser.Sheet sheet = ReconciliationFileParser.read(bytes, fileName);
        if (!ReconciliationFileParser.hasAmountColumn(sheet.headers())) {
            throw new BusinessException("No encontré la columna de monto. Columnas reconocidas: "
                    + String.join(", ", ReconciliationFileParser.recognizedColumns()) + ". Columnas del archivo: "
                    + String.join(", ", sheet.headers().stream().filter(h -> !h.isEmpty()).toList()) + ".");
        }
        if (sheet.rows().size() > props.getMaxImportRows()) {
            throw new BusinessException("El archivo tiene más de " + props.getMaxImportRows() + " filas. Divídalo en partes.");
        }

        ReconImportFileEntity file = importFileRepository.save(ReconImportFileEntity.builder().fileName(fileName == null ? "archivo" : fileName)
                .sha256(sha).sourceType(sourceType).rowsTotal(sheet.rows().size()).importedBy(actor.id()).build());

        List<RowReport> report = new ArrayList<>();
        List<ReconReceiptEntity> fresh = new ArrayList<>();
        List<Integer> freshRows = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();
        List<Object[]> parsed = new ArrayList<>();
        int duplicated = 0;
        int rejected = 0;
        for (Map<String, String> row : sheet.rows()) {
            int rowNumber = Integer.parseInt(row.getOrDefault("#fila", "0"));
            try {
                ParsedReceipt p = ReconciliationFileParser.map(row);
                parsed.add(new Object[]{rowNumber, p, rowHash(sourceType, p)});
            } catch (RowError e) {
                rejected++;
                addReport(report, rowNumber, "RECHAZADA", e.getMessage());
            }
        }
        Set<String> existingHashes = new HashSet<>();
        List<String> allHashes = parsed.stream().map(o -> (String) o[2]).toList();
        for (int i = 0; i < allHashes.size(); i += 500) {
            receiptRepository.findByRowHashIn(allHashes.subList(i, Math.min(allHashes.size(), i + 500)))
                    .forEach(r -> existingHashes.add(r.getRowHash()));
        }
        for (Object[] o : parsed) {
            int rowNumber = (Integer) o[0];
            ParsedReceipt p = (ParsedReceipt) o[1];
            String hash = (String) o[2];
            if (!seenHashes.add(hash) || existingHashes.contains(hash)) {
                duplicated++;
                addReport(report, rowNumber, "DUPLICADA", "Ya existe una fila idéntica (misma guía, referencia, monto y fecha).");
                continue;
            }
            fresh.add(ReconReceiptEntity.builder().importFileId(file.getId()).sourceType(sourceType).rowHash(hash)
                    .carrier(p.carrier()).guideNumber(p.guide()).reference(p.reference()).authorizationNumber(p.authorization())
                    .providerTxnId(p.providerTxn()).shipmentNumber(p.shipmentNumber()).receivedDate(p.date()).amount(p.amount())
                    .shippingAmount(p.shipping()).rawPayload(toJson(p.raw())).createdBy(actor.id()).build());
            freshRows.add(rowNumber);
        }
        receiptRepository.saveAll(fresh);
        receiptRepository.flush();

        int auto = 0;
        int ambiguous = 0;
        int blocked = 0;
        int unmatched = 0;
        Set<Long> touched = new LinkedHashSet<>();
        Index index = buildIndex(fresh);
        for (int i = 0; i < fresh.size(); i++) {
            ReconReceiptEntity r = fresh.get(i);
            int rowNumber = freshRows.get(i);
            ReconciliationMatcher.Outcome outcome = match(r, index).outcome();
            switch (outcome.kind()) {
                case EXACT -> {
                    applyInternal(r, outcome.caseId(), r.getAmount(), outcome.method());
                    touched.add(outcome.caseId());
                    auto++;
                }
                case AMBIGUOUS -> {
                    ambiguous++;
                    addReport(report, rowNumber, "AMBIGUA", outcome.reason());
                }
                case BLOCKED -> {
                    blocked++;
                    addReport(report, rowNumber, "BLOQUEADA", outcome.reason());
                }
                default -> {
                    unmatched++;
                    addReport(report, rowNumber, "SIN_COINCIDENCIA", outcome.reason());
                }
            }
        }
        file.setRowsMatched(auto);
        importFileRepository.save(file);
        recomputeCases(touched);
        events.record(null, null, "ARCHIVO_IMPORTADO", null, null, Map.of("archivo", file.getFileName(), "fuente", sourceType.name(),
                "filas", sheet.rows().size(), "importadas", fresh.size(), "aplicadas", auto, "rechazadas", rejected));
        return new ImportResult(file.getId(), false, sheet.rows().size(), fresh.size(), duplicated, rejected, auto, ambiguous, blocked,
                unmatched, report);
    }

    /** Vuelve a intentar la asociación exacta de los fondos que quedaron sin aplicar (por ejemplo tras recuperar guías). */
    @Transactional
    public ImportResult autoApplyPending() {
        int auto = 0;
        int ambiguous = 0;
        int blocked = 0;
        int unmatched = 0;
        Set<Long> touched = new LinkedHashSet<>();
        List<ReconReceiptEntity> pending = receiptRepository.findUnapplied(PageRequest.of(0, 2000));
        Index index = buildIndex(pending);
        for (ReconReceiptEntity r : pending) {
            ReconciliationMatcher.Outcome o = match(r, index).outcome();
            switch (o.kind()) {
                case EXACT -> {
                    BigDecimal remaining = remaining(r);
                    if (remaining.signum() > 0) {
                        applyInternal(r, o.caseId(), remaining, o.method());
                        touched.add(o.caseId());
                        auto++;
                    }
                }
                case AMBIGUOUS -> ambiguous++;
                case BLOCKED -> blocked++;
                default -> unmatched++;
            }
        }
        recomputeCases(touched);
        return new ImportResult(null, false, pending.size(), 0, 0, 0, auto, ambiguous, blocked, unmatched, List.of());
    }

    // ───────── captura manual ─────────

    @Transactional
    public ReceiptMatch createManual(ReceiptRequest req) throws BusinessException {
        if (req == null || req.sourceType() == null) throw new BusinessException("Indique el tipo de fuente.");
        if (req.amount() == null || req.amount().signum() <= 0) throw new BusinessException("El monto debe ser mayor a cero.");
        if (req.amount().stripTrailingZeros().scale() > 2) throw new BusinessException("El monto no puede tener más de 2 decimales.");
        if (req.receivedDate() != null && req.receivedDate().isAfter(GuatemalaDateTime.today())) {
            throw new BusinessException("La fecha del fondo no puede ser futura.");
        }
        ReconReceiptEntity r = receiptRepository.save(ReconReceiptEntity.builder().sourceType(req.sourceType()).carrier(trim(req.carrier()))
                .guideNumber(trim(req.guideNumber())).reference(trim(req.reference())).authorizationNumber(trim(req.authorizationNumber()))
                .receivedDate(req.receivedDate()).amount(money(req.amount())).shippingAmount(req.shippingAmount() == null ? null : money(req.shippingAmount()))
                .rawPayload(toJson(Map.of("captura", "manual"))).createdBy(actor.id()).build());
        events.record(null, null, "FONDO_CAPTURADO", null, null, Map.of("fondo", r.getId(), "monto", r.getAmount().toPlainString()));
        ReconciliationMatcher.Outcome o = match(r).outcome();
        if (o.kind() == ReconciliationMatcher.Kind.EXACT) {
            applyInternal(r, o.caseId(), r.getAmount(), o.method());
            recomputeCases(Set.of(o.caseId()));
        }
        return describe(r);
    }

    // ───────── pago escrito a mano ─────────

    /** Registra un pago recibido y lo aplica a un caso concreto (el que el usuario tiene abierto). */
    @Transactional
    public QuickReceiptResult recordForCase(Long caseId, CaseReceiptRequest req) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = caseRepository.findById(caseId).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + caseId));
        if (c.isClosed()) throw new BusinessException("El caso está en un lote cerrado y no se puede modificar.");
        if (c.getExcludedReason() != null) throw new BusinessException("El caso está excluido de la conciliación.");
        if (req == null || req.amount() == null || req.amount().signum() <= 0) throw new BusinessException("Escriba el monto recibido (mayor a cero).");
        if (req.amount().stripTrailingZeros().scale() > 2) throw new BusinessException("El monto no puede tener más de 2 decimales.");
        LocalDate date = req.receivedDate() == null ? GuatemalaDateTime.today() : req.receivedDate();
        if (date.isAfter(GuatemalaDateTime.today())) throw new BusinessException("La fecha del pago no puede ser futura.");
        String reference = trim(req.reference());
        rejectRepeatedPayment(c, reference, money(req.amount()));

        Recon.SourceType source = req.sourceType() != null ? req.sourceType() : inferSource(c);
        ReconReceiptEntity r = receiptRepository.save(ReconReceiptEntity.builder().sourceType(source).reference(reference).receivedDate(date)
                .amount(money(req.amount())).shippingAmount(req.shippingAmount() == null ? null : money(req.shippingAmount()))
                .rawPayload(toJson(Map.of("captura", "escrita en el caso"))).createdBy(actor.id()).build());
        events.record(c.getId(), null, "FONDO_CAPTURADO", c.getStatus(), c.getStatus(),
                Map.of("fondo", r.getId(), "monto", r.getAmount().toPlainString(), "fuente", source.name()));
        applyInternal(r, c.getId(), r.getAmount(), Recon.MatchMethod.MANUAL);
        recomputeCases(Set.of(c.getId()));
        return resultOf(caseRepository.findById(caseId).orElseThrow(), r.getAmount());
    }

    /** Captura rápida: encuentra la venta por el ENVL o la guía escritos y registra el pago. Si hay duda, no aplica nada. */
    @Transactional
    public QuickReceiptResult recordByIdentifier(QuickReceiptRequest req) throws ResourceNotFoundException, BusinessException {
        if (req == null || blank(req.identifier())) throw new BusinessException("Escriba el ENVL o la guía de la venta.");
        String id = req.identifier().trim();
        List<ReconCaseEntity> found = new ArrayList<>(caseRepository.findByShipmentNumberIgnoreCase(id));
        if (found.isEmpty()) {
            Set<String> keys = new HashSet<>(List.of(id, norm(id)));
            for (ReconCaseEntity c : caseRepository.findByGuideNumberIn(keys)) {
                if (norm(c.getGuideNumber()).equals(norm(id))) found.add(c);
            }
        }
        if (found.isEmpty()) {
            throw new BusinessException("No encontré una venta con el ENVL o la guía «" + id + "». Revise lo escrito o genere los casos del período si es una venta nueva.");
        }
        if (found.size() > 1) {
            throw new BusinessException("Hay " + found.size() + " ventas con «" + id + "» ("
                    + found.stream().map(c -> c.getShipmentNumber() == null ? "caso " + c.getId() : c.getShipmentNumber()).collect(Collectors.joining(", "))
                    + "). Ábralas en la Bandeja y registre el pago en la correcta.");
        }
        return recordForCase(found.get(0).getId(), new CaseReceiptRequest(req.sourceType(), req.reference(), req.receivedDate(), req.amount(), req.shippingAmount()));
    }

    /** Fuente probable según cómo se cobró la venta: contra entrega → transportista (o efectivo si es motorista), depósito → banco, tarjeta → tarjeta. */
    static Recon.SourceType inferSource(ReconCaseEntity c) {
        if (isCod(c.getPaymentMethod())) return carrierKey(c.getCarrier()).equals("MOTORISTA") ? Recon.SourceType.CASH : Recon.SourceType.CARRIER;
        if (isBankPayment(c.getPaymentMethod())) return Recon.SourceType.BANK;
        if (isCardPayment(c.getPaymentMethod())) return Recon.SourceType.CARD;
        return Recon.SourceType.MANUAL;
    }

    /** Evita el error más común al escribir: registrar dos veces el mismo pago (misma referencia y monto). */
    private void rejectRepeatedPayment(ReconCaseEntity c, String reference, BigDecimal amount) throws BusinessException {
        if (blank(reference)) return;
        List<ReconReceiptApplicationEntity> active = applicationRepository.findByCaseIdAndStatus(c.getId(), Recon.RowStatus.ACTIVE);
        if (active.isEmpty()) return;
        for (ReconReceiptEntity r : receiptRepository.findByIdIn(active.stream().map(ReconReceiptApplicationEntity::getReceiptId).toList())) {
            if (r.getReference() != null && norm(r.getReference()).equals(norm(reference)) && sameMoney(r.getAmount(), amount)) {
                throw new BusinessException("Este caso ya tiene un pago de " + q(amount) + " con la referencia " + reference
                        + ". Si fue un error, anule el pago anterior en el detalle del caso.");
            }
        }
    }

    private QuickReceiptResult resultOf(ReconCaseEntity c, BigDecimal paid) {
        BigDecimal diff = money(c.getReceivedAmount()).subtract(money(c.getExpectedAmount()));
        String msg = "Pago de " + q(paid) + " aplicado a " + (c.getShipmentNumber() == null ? "el caso" : c.getShipmentNumber()) + ". Cobrado " + q(c.getExpectedAmount())
                + ", recibido " + q(c.getReceivedAmount()) + ": " + (diff.signum() == 0 ? "cuadra al centavo."
                : (diff.signum() < 0 ? "falta " : "sobra ") + q(diff.abs()) + ".");
        return new QuickReceiptResult(c.getId(), c.getShipmentNumber(), money(c.getExpectedAmount()), money(c.getReceivedAmount()), diff,
                c.getStatus().name(), msg);
    }

    // ───────── consultas ─────────

    @Transactional(readOnly = true)
    public List<ReceiptMatch> unapplied(int limit) {
        return receiptRepository.findUnapplied(PageRequest.of(0, Math.max(1, Math.min(limit, 500)))).stream().map(this::describe).toList();
    }

    @Transactional(readOnly = true)
    public ReceiptMatch find(Long receiptId) throws ResourceNotFoundException {
        return describe(receiptRepository.findById(receiptId).orElseThrow(() -> new ResourceNotFoundException("Fondo no encontrado: " + receiptId)));
    }

    // ───────── aplicar y anular ─────────

    /** Aplica un fondo a un caso elegido por una persona (el caso de coincidencias ambiguas o sin identificador). */
    @Transactional
    public void apply(Long caseId, Long receiptId, BigDecimal amount) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = caseRepository.findById(caseId).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + caseId));
        ReconReceiptEntity r = receiptRepository.findById(receiptId).orElseThrow(() -> new ResourceNotFoundException("Fondo no encontrado: " + receiptId));
        if (c.isClosed()) throw new BusinessException("El caso está en un lote cerrado.");
        if (c.getExcludedReason() != null) throw new BusinessException("El caso está excluido de la conciliación.");
        if (r.getStatus() != Recon.RowStatus.ACTIVE) throw new BusinessException("El fondo está anulado.");
        BigDecimal remaining = remaining(r);
        BigDecimal toApply = amount == null ? remaining : money(amount);
        if (toApply.signum() <= 0) throw new BusinessException("El monto a aplicar debe ser mayor a cero.");
        if (toApply.compareTo(remaining) > 0) {
            throw new BusinessException("El fondo solo tiene " + q(remaining) + " sin aplicar y se intentó aplicar " + q(toApply) + ".");
        }
        boolean already = applicationRepository.findByCaseIdAndStatus(caseId, Recon.RowStatus.ACTIVE).stream()
                .anyMatch(a -> a.getReceiptId().equals(receiptId));
        if (already) throw new BusinessException("Este fondo ya está aplicado a este caso.");
        applyInternal(r, caseId, toApply, Recon.MatchMethod.MANUAL);
        recomputeCases(Set.of(caseId));
    }

    @Transactional
    public void voidApplication(Long applicationId, String reason) throws ResourceNotFoundException, BusinessException {
        ReconReceiptApplicationEntity a = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Aplicación no encontrada: " + applicationId));
        if (reason == null || reason.trim().length() < 5) throw new BusinessException("Escriba el motivo de la anulación.");
        if (a.getStatus() != Recon.RowStatus.ACTIVE) throw new BusinessException("La aplicación ya está anulada.");
        ReconCaseEntity c = caseRepository.findById(a.getCaseId()).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + a.getCaseId()));
        if (c.isClosed()) throw new BusinessException("El caso está en un lote cerrado. Solo se corrige anulando el lote completo.");
        a.setStatus(Recon.RowStatus.VOID);
        a.setVoidReason(reason.trim());
        a.setVoidedAt(java.time.LocalDateTime.now());
        a.setVoidedBy(actor.id());
        applicationRepository.save(a);
        events.record(c.getId(), null, "APLICACION_ANULADA", c.getStatus(), c.getStatus(),
                Map.of("fondo", a.getReceiptId(), "monto", a.getAmountApplied().toPlainString(), "motivo", reason.trim()));
        recomputeCases(Set.of(c.getId()));
    }

    @Transactional
    public void voidReceipt(Long receiptId, String reason) throws ResourceNotFoundException, BusinessException {
        ReconReceiptEntity r = receiptRepository.findById(receiptId).orElseThrow(() -> new ResourceNotFoundException("Fondo no encontrado: " + receiptId));
        if (reason == null || reason.trim().length() < 5) throw new BusinessException("Escriba el motivo de la anulación.");
        if (!applicationRepository.findByReceiptIdAndStatus(receiptId, Recon.RowStatus.ACTIVE).isEmpty()) {
            throw new BusinessException("El fondo tiene aplicaciones activas. Anúlelas primero.");
        }
        r.setStatus(Recon.RowStatus.VOID);
        r.setVoidReason(reason.trim());
        r.setVoidedAt(java.time.LocalDateTime.now());
        r.setVoidedBy(actor.id());
        receiptRepository.save(r);
        events.record(null, null, "FONDO_ANULADO", null, null, Map.of("fondo", receiptId, "motivo", reason.trim()));
    }

    // ───────── asociación ─────────

    record MatchResult(ReconciliationMatcher.Outcome outcome) {
    }

    /** Casos y ventas encontrados de una vez para todos los fondos que se van a asociar (evita consultar fondo por fondo). */
    record Index(Map<String, List<ReconCaseEntity>> byGuide, Map<String, List<ReconCaseEntity>> byAuth,
                 Map<String, List<ReconCaseEntity>> byEnvl) {
    }

    Index buildIndex(Collection<ReconReceiptEntity> receipts) {
        Set<String> guides = new LinkedHashSet<>();
        Set<String> auths = new LinkedHashSet<>();
        Set<String> envls = new LinkedHashSet<>();
        for (ReconReceiptEntity r : receipts) {
            if (!blank(r.getGuideNumber())) {
                guides.add(r.getGuideNumber().trim());
                guides.add(norm(r.getGuideNumber()));
            }
            if (!blank(r.getShipmentNumber())) addEnvl(envls, r.getShipmentNumber());
            for (String v : new String[]{r.getReference(), r.getAuthorizationNumber(), r.getProviderTxnId()}) {
                if (blank(v)) continue;
                if (ENVL_PATTERN.matcher(v.trim()).matches()) {
                    addEnvl(envls, v);
                } else {
                    auths.add(v.trim());
                    auths.add(v.trim().toUpperCase(Locale.ROOT));
                    auths.add(v.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, List<ReconCaseEntity>> byGuide = new HashMap<>();
        for (List<String> part : chunks(new ArrayList<>(guides))) {
            for (ReconCaseEntity c : caseRepository.findByGuideNumberIn(part)) {
                if (!blank(c.getGuideNumber())) byGuide.computeIfAbsent(norm(c.getGuideNumber()), k -> new ArrayList<>()).add(c);
            }
        }
        Map<String, List<ReconCaseEntity>> byEnvl = new HashMap<>();
        for (List<String> part : chunks(new ArrayList<>(envls))) {
            for (ReconCaseEntity c : caseRepository.findByShipmentNumberIn(part)) {
                if (!blank(c.getShipmentNumber())) {
                    byEnvl.computeIfAbsent(c.getShipmentNumber().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
                }
            }
        }
        Map<String, List<ReconCaseEntity>> byAuth = new HashMap<>();
        for (List<String> part : chunks(new ArrayList<>(auths))) {
            Map<Long, String> authBySale = new HashMap<>();
            for (OnlineSaleEntity s : saleRepository.findByPaymentAuthorizationIn(part)) {
                authBySale.put(s.getId(), s.getPaymentAuthorization().trim().toLowerCase(Locale.ROOT));
            }
            if (authBySale.isEmpty()) continue;
            for (ReconCaseEntity c : caseRepository.findByOnlineSaleIdIn(authBySale.keySet())) {
                byAuth.computeIfAbsent(authBySale.get(c.getOnlineSaleId()), k -> new ArrayList<>()).add(c);
            }
        }
        return new Index(byGuide, byAuth, byEnvl);
    }

    private static void addEnvl(Set<String> envls, String v) {
        envls.add(v.trim());
        envls.add(v.trim().toUpperCase(Locale.ROOT));
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 500) out.add(all.subList(i, Math.min(all.size(), i + 500)));
        return out;
    }

    MatchResult match(ReconReceiptEntity r) {
        return match(r, buildIndex(List.of(r)));
    }

    MatchResult match(ReconReceiptEntity r, Index idx) {
        List<Hit> byGuide = new ArrayList<>();
        if (!blank(r.getGuideNumber())) {
            for (ReconCaseEntity c : idx.byGuide().getOrDefault(norm(r.getGuideNumber()), List.of())) {
                if (carrierCompatible(r, c)) byGuide.add(hit(c));
            }
        }
        List<Hit> byRef = new ArrayList<>();
        List<Hit> byEnvl = new ArrayList<>();
        if (!blank(r.getShipmentNumber())) {
            for (ReconCaseEntity c : idx.byEnvl().getOrDefault(r.getShipmentNumber().trim().toUpperCase(Locale.ROOT), List.of())) byEnvl.add(hit(c));
        }
        for (String v : new String[]{r.getReference(), r.getAuthorizationNumber(), r.getProviderTxnId()}) {
            if (blank(v)) continue;
            if (ENVL_PATTERN.matcher(v.trim()).matches()) {
                // el transportista devolvió nuestro ENVL como referencia
                for (ReconCaseEntity c : idx.byEnvl().getOrDefault(v.trim().toUpperCase(Locale.ROOT), List.of())) byEnvl.add(hit(c));
            } else {
                for (ReconCaseEntity c : idx.byAuth().getOrDefault(v.trim().toLowerCase(Locale.ROOT), List.of())) byRef.add(hit(c));
            }
        }
        return new MatchResult(ReconciliationMatcher.decide(distinct(byGuide), distinct(byRef), distinct(byEnvl)));
    }

    private static Hit hit(ReconCaseEntity c) {
        return new Hit(c.getId(), c.getExcludedReason() != null, c.isClosed());
    }

    private static List<Hit> distinct(List<Hit> hits) {
        return new ArrayList<>(hits.stream().collect(Collectors.toMap(Hit::caseId, h -> h, (a, b) -> a, LinkedHashMap::new)).values());
    }

    private static boolean carrierCompatible(ReconReceiptEntity r, ReconCaseEntity c) {
        return blank(r.getCarrier()) || blank(c.getCarrier()) || carrierKey(r.getCarrier()).equals(carrierKey(c.getCarrier()));
    }

    private void applyInternal(ReconReceiptEntity r, Long caseId, BigDecimal amount, Recon.MatchMethod method) {
        applicationRepository.save(ReconReceiptApplicationEntity.builder().caseId(caseId).receiptId(r.getId()).amountApplied(money(amount))
                .matchMethod(method).appliedBy(actor.id()).build());
        events.record(caseId, null, "FONDO_APLICADO", null, null,
                Map.of("fondo", r.getId(), "monto", money(amount).toPlainString(), "metodo", method.name()));
    }

    private void recomputeCases(Collection<Long> caseIds) {
        if (caseIds.isEmpty()) return;
        recompute.recomputeAll(caseRepository.findAllById(caseIds));
    }

    // ───────── descripción para la pantalla ─────────

    private ReceiptMatch describe(ReconReceiptEntity r) {
        BigDecimal applied = applied(r);
        BigDecimal remaining = money(r.getAmount()).subtract(applied);
        ReceiptDto dto = new ReceiptDto(r.getId(), r.getSourceType().name(), r.getCarrier(), r.getGuideNumber(), r.getReference(),
                r.getAuthorizationNumber(), r.getReceivedDate(), money(r.getAmount()), r.getShippingAmount(), applied, remaining,
                r.getStatus().name(), r.getImportFileId());
        if (remaining.signum() <= 0 || r.getStatus() != Recon.RowStatus.ACTIVE) {
            return new ReceiptMatch(dto, "APLICADO", "El fondo ya está aplicado por completo.", List.of());
        }
        ReconciliationMatcher.Outcome o = match(r).outcome();
        List<CandidateDto> candidates = new ArrayList<>();
        if (o.kind() == ReconciliationMatcher.Kind.AMBIGUOUS || o.kind() == ReconciliationMatcher.Kind.EXACT || o.kind() == ReconciliationMatcher.Kind.BLOCKED) {
            for (ReconCaseEntity c : caseRepository.findAllById(o.candidateCaseIds())) candidates.add(candidate(c, "Coincide por identificador"));
        } else {
            for (ReconCaseEntity c : caseRepository.findOpenWithoutFundsByExpectedAmount(money(r.getAmount()), PageRequest.of(0, 5))) {
                if (!carrierCompatible(r, c)) continue;
                candidates.add(candidate(c, "Mismo monto " + q(r.getAmount()) + ". No se aplica solo: confirme que es la venta correcta."));
            }
        }
        return new ReceiptMatch(dto, o.kind().name(), o.reason(), candidates);
    }

    private CandidateDto candidate(ReconCaseEntity c, String reason) {
        return new CandidateDto(c.getId(), c.getShipmentNumber(), c.getSaleNumber(), c.getSaleDate(), c.getExpectedAmount(), c.getCarrier(),
                c.getSalesperson(), reason);
    }

    private BigDecimal applied(ReconReceiptEntity r) {
        return money(applicationRepository.findByReceiptIdAndStatus(r.getId(), Recon.RowStatus.ACTIVE).stream()
                .map(ReconReceiptApplicationEntity::getAmountApplied).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal remaining(ReconReceiptEntity r) {
        return money(r.getAmount()).subtract(applied(r));
    }

    // ───────── utilidades ─────────

    static String rowHash(Recon.SourceType source, ParsedReceipt p) {
        return sha256(String.join("|", source.name(), norm(p.guide()), norm(p.reference()), norm(p.authorization()), norm(p.providerTxn()),
                norm(p.shipmentNumber()), String.valueOf(p.date()), money(p.amount()).toPlainString(),
                p.shipping() == null ? "" : money(p.shipping()).toPlainString(), norm(p.carrier())));
    }

    private static void addReport(List<RowReport> report, int row, String status, String message) {
        if (report.size() < REPORT_LIMIT) report.add(new RowReport(row, status, message));
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return String.valueOf(map);
        }
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }
}
