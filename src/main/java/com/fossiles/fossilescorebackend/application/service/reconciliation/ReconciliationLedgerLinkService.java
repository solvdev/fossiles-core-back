package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerResponse;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerRow;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.LedgerLinkDtos.*;
import com.fossiles.fossilescorebackend.application.service.reconciliation.CaseFacts.ReceiptFact;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.CaseReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.InvalidCaseRef;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.ReplaceReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.SettleResult;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.onlineledger.OnlineLedgerLogService;
import com.fossiles.fossilescorebackend.application.service.onlineledger.OnlineLedgerPeriodService;
import com.fossiles.fossilescorebackend.application.service.onlineledger.OnlineSaleLedgerService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineLedgerLogEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineLedgerPeriodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineLedgerPeriodRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/**
 * Liga un periodo de revisión del libro de ventas en línea con la Conciliación.
 *
 * <p>El libro es lo que Contabilidad revisó, así que manda: {@link #preview} compara cada venta del periodo contra su caso de
 * conciliación (monto cobrado, depósito real contra lo recibido, envío real, estado) y dice qué quedaría igual al libro;
 * {@link #apply} sobrescribe la Conciliación con el libro, liquida (lote + descarga + Excel) y, si se pide, cierra la revisión
 * del libro. Es todo o nada: si alguna venta elegida no queda lista, no se cambia nada y se informa cuáles fueron.
 *
 * <p>Lo que la Conciliación toma de la venta misma (guía, ENVL, transportista, vendedor) no se sobrescribe: si el libro lo
 * corrigió solo se muestra como diferencia informativa, porque el libro nunca modifica la venta.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationLedgerLinkService {

    private static final Set<String> TRACKED = Set.of("ENVIADO", "ENTREGADO", "DEVOLUCION", "ANULADA", "CANCELADO");
    private static final String AUTO_EXCLUDE_PREFIX = "Venta en estado";
    private static final int CHUNK = 500;

    public static final String LISTA = "LISTA";
    public static final String LISTA_CON_CAMBIOS = "LISTA_CON_CAMBIOS";
    public static final String EXCLUIR = "EXCLUIR";
    public static final String NO_LISTA = "NO_LISTA";
    public static final String SIN_REVISAR = "SIN_REVISAR";
    public static final String YA_LIQUIDADA = "YA_LIQUIDADA";
    public static final String NO_ENTRA = "NO_ENTRA";
    public static final String NO_DESPACHADA = "NO_DESPACHADA";

    /** Arreglos que se pueden hacer desde la pantalla de liquidar (ver {@link #resolve}). */
    public static final String VOID_AND_EXCLUDE = "VOID_AND_EXCLUDE";
    public static final String MARK_REFUND = "MARK_REFUND";
    public static final String MARK_VOIDED = "MARK_VOIDED";
    /** Evento del caso que deja una venta devuelta/anulada con fondos fuera de la liquidación como reembolso pendiente. */
    static final String REFUND_EVENT = "REEMBOLSO_PENDIENTE";
    private static final Set<String> MONEY_RULES = Set.of("R06", "R16");

    private final OnlineSaleLedgerService ledgerService;
    private final OnlineLedgerPeriodService periodService;
    private final OnlineLedgerPeriodRepository periodRepository;
    private final OnlineLedgerLogService logService;
    private final ReconCaseRepository caseRepository;
    private final ReconBatchRepository batchRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconReceiptRepository receiptRepository;
    private final ReconDifferenceRepository differenceRepository;
    private final ReconciliationCaseService caseService;
    private final ReconciliationReceiptService receiptService;
    private final ReconciliationSettlementService settlementService;
    private final ReconciliationBatchService batchService;
    private final ReconEventService events;
    private final ReconEventRepository eventRepository;
    private final ReconciliationRecomputeService recompute;
    private final ReconciliationRuleEngine engine;
    private final ReconciliationAccessGuard guard;
    private final ObjectMapper objectMapper;

    // ───────── permisos ─────────

    private void assertCanPreview() throws BusinessException {
        guard.assertCanView();
        ledgerService.assertCanView();
    }

    private void assertCanApply() throws BusinessException {
        assertCanPreview();
        guard.assertCanReconcile();
        guard.assertCanImport();
        guard.assertCanClose();
    }

    // ───────── periodos ─────────

    /** Periodos de revisión del libro con su avance y los lotes que ya se liquidaron con cada uno. */
    @Transactional(readOnly = true)
    public List<PeriodOption> periods() throws BusinessException {
        assertCanPreview();
        List<OnlineLedgerPeriodEntity> list = periodRepository.findTop60ByOrderByDateFromDesc();
        Map<Long, List<ReconBatchEntity>> batches = batchService.batchesByLedgerPeriod(list.stream().map(OnlineLedgerPeriodEntity::getId).toList());
        List<PeriodOption> out = new ArrayList<>();
        for (OnlineLedgerPeriodEntity p : list) out.add(option(p, batches.getOrDefault(p.getId(), List.of())));
        return out;
    }

    private PeriodOption option(OnlineLedgerPeriodEntity p, List<ReconBatchEntity> batches) {
        long[] pr = logService.progress(p.getDateFrom(), p.getDateTo());
        List<LinkedBatch> linked = batches.stream().map(b -> new LinkedBatch(b.getId(), b.getCode(), b.getStatus().name(),
                caseRepository.findByBatchId(b.getId()).size(), b.getTotalExpected(), b.getTotalReceived(), b.getClosedAt())).toList();
        return new PeriodOption(p.getId(), p.getDateFrom(), p.getDateTo(), p.getLabel(), p.getStatus(), p.getClosedByName(), p.getClosedAt(),
                pr[0], pr[1], linked);
    }

    // ───────── comparar ─────────

    @Transactional(readOnly = true)
    public Preview preview(Long periodId, boolean assumeEqual) throws BusinessException, ResourceNotFoundException {
        assertCanPreview();
        OnlineLedgerPeriodEntity p = find(periodId);
        Evaluation ev = evaluate(p, assumeEqual);
        List<String> warnings = new ArrayList<>();
        if (!p.isClosed() && ev.unreviewed > 0) {
            warnings.add(ev.unreviewed + " venta(s) sin marcar «Revisado» en el libro: no entran a la liquidación.");
        }
        if (ev.plans.stream().anyMatch(x -> NO_DESPACHADA.equals(x.state))) {
            warnings.add("Hay ventas del periodo que aún no están despachadas: no se concilian ni se liquidan.");
        }
        boolean canApply = ev.plans.stream().anyMatch(x -> x.selectable()) && canPerform();
        return new Preview(option(p, batchService.batchesByLedgerPeriod(List.of(p.getId())).getOrDefault(p.getId(), List.of())),
                ev.summary(), ev.plans.stream().map(x -> x.row()).toList(), canApply,
                ev.closeBlocker == null && !p.isClosed() && periodService.canManage(), ev.closeBlocker, assumeEqual, warnings);
    }

    private boolean canPerform() {
        try {
            guard.assertCanReconcile();
            guard.assertCanImport();
            guard.assertCanClose();
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    // ───────── aplicar ─────────

    @Transactional(rollbackFor = Exception.class)
    public ApplyResult apply(Long periodId, ApplyRequest req, String idempotencyKey) throws BusinessException, ResourceNotFoundException {
        assertCanApply();
        if (req == null || req.saleIds() == null || req.saleIds().isEmpty()) throw new BusinessException("Elija las ventas por liquidar.");
        OnlineLedgerPeriodEntity p = find(periodId);
        boolean wantsClose = req.closePeriod() && !p.isClosed();
        if (wantsClose && !periodService.canManage()) {
            throw new BusinessException("Solo Administración y Contabilidad pueden cerrar la revisión del libro.");
        }
        Evaluation ev = evaluate(p, req.assumeEqual());
        Map<Long, Plan> bySale = ev.plans.stream().collect(Collectors.toMap(x -> x.saleId, x -> x, (a, b) -> a, LinkedHashMap::new));
        LinkedHashSet<Long> chosen = new LinkedHashSet<>(req.saleIds());

        List<Blocked> blocked = new ArrayList<>();
        for (Long id : chosen) {
            Plan x = bySale.get(id);
            if (x == null) blocked.add(new Blocked(id, null, null, "La venta no pertenece al periodo."));
            else if (!x.selectable()) blocked.add(new Blocked(id, x.saleNumber, x.shipmentNumber, "Ya no está lista: " + x.reason));
        }
        if (!blocked.isEmpty()) return notApplied(blocked, "No se cambió nada: algunas ventas elegidas ya no están listas.");

        if (wantsClose) {
            if (ev.closeBlocker != null) throw new BusinessException("No se puede cerrar la revisión del libro: " + ev.closeBlocker + ".");
            long left = ev.plans.stream().filter(Plan::selectable).filter(x -> !chosen.contains(x.saleId)).count();
            if (left > 0) {
                throw new BusinessException("Para cerrar la revisión hay que liquidar todas las ventas listas del periodo; faltan " + left
                        + " sin marcar. Márquelas o desmarque «Cerrar la revisión del libro».");
            }
        }

        if (chosen.stream().map(bySale::get).anyMatch(x -> x.caseId == null)) {
            caseService.generate(p.getDateFrom(), p.getDateTo());
        }
        Map<Long, ReconCaseEntity> cases = new HashMap<>();
        for (ReconCaseEntity c : caseRepository.findByOnlineSaleIdIn(chosen)) cases.put(c.getOnlineSaleId(), c);

        int excluded = 0;
        int overwritten = 0;
        List<Long> settleIds = new ArrayList<>();
        for (Long saleId : chosen) {
            Plan x = bySale.get(saleId);
            ReconCaseEntity c = cases.get(saleId);
            if (c == null) {
                blocked.add(new Blocked(saleId, x.saleNumber, x.shipmentNumber, "No se pudo crear su caso de conciliación (¿la venta no está despachada?)."));
                continue;
            }
            try {
                List<String> changes = applyPlan(c, x, p);
                if (!changes.isEmpty()) overwritten++;
                if (EXCLUIR.equals(x.state)) excluded++;
                else settleIds.add(c.getId());
            } catch (BusinessException | ResourceNotFoundException e) {
                blocked.add(new Blocked(saleId, x.saleNumber, x.shipmentNumber, e.getMessage()));
            }
        }
        if (!blocked.isEmpty()) return notApplied(blocked, "No se cambió nada: algunas ventas no se pudieron igualar al libro.");

        SettleResult settled = null;
        if (!settleIds.isEmpty()) {
            settled = settlementService.settle(settleIds, idempotencyKey, p.getId());
            if (settled.skipped() != null && !settled.skipped().isEmpty()) {
                Map<Long, Plan> byCase = new HashMap<>();
                for (Plan x : bySale.values()) if (cases.get(x.saleId) != null) byCase.put(cases.get(x.saleId).getId(), x);
                for (InvalidCaseRef s : settled.skipped()) {
                    Plan x = byCase.get(s.caseId());
                    blocked.add(new Blocked(x == null ? null : x.saleId, x == null ? null : x.saleNumber, s.shipmentNumber(), s.reason()));
                }
                return notApplied(blocked, "No se cambió nada: algunas ventas no quedan listas para liquidar con lo del libro.");
            }
        }

        boolean closed = false;
        if (wantsClose) {
            periodService.close(p.getId());
            closed = true;
        }
        String code = settled == null ? null : settled.close().batch().code();
        Long batchId = settled == null ? null : settled.close().batch().id();
        BigDecimal amount = settled == null ? BigDecimal.ZERO : settled.close().batch().expected();
        int liquidated = settled == null ? 0 : settled.close().closedCases();
        String detail = "Liquidó " + liquidated + " ventas por " + q(amount) + (code == null ? "" : " en el lote " + code)
                + "; " + overwritten + " con datos igualados al libro" + (excluded > 0 ? "; " + excluded + " excluidas por devolución/anulación" : "")
                + (closed ? ". Cerró la revisión." : ".");
        logService.recordPeriodEvent(p, OnlineLedgerLogEntity.LIQUIDATE, detail);
        events.record(null, batchId, "LIBRO_LIQUIDADO", null, null, Map.of("periodo", p.getLabel(), "ventas", liquidated, "igualadas", overwritten));
        return new ApplyResult(true, batchId, code, liquidated, excluded, overwritten, amount, closed,
                "Listo: " + detail, List.of());
    }

    private ApplyResult notApplied(List<Blocked> blocked, String message) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return new ApplyResult(false, null, null, 0, 0, 0, BigDecimal.ZERO, false, message, blocked);
    }

    // ───────── arreglar un bloqueo desde la pantalla ─────────

    /**
     * Resuelve el bloqueo de una venta sin salir de la pantalla de liquidar. Solo se acepta un arreglo que la vista previa ofrece
     * para esa venta en este momento; el motivo queda en el historial del caso y en la bitácora del libro.
     */
    @Transactional(rollbackFor = Exception.class)
    public ResolveResult resolve(Long periodId, ResolveRequest req) throws BusinessException, ResourceNotFoundException {
        assertCanApply();
        if (req == null || req.saleId() == null || blank(req.action())) throw new BusinessException("Elija la venta y el arreglo.");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.length() < 10) throw new BusinessException("Escriba el motivo (mínimo 10 caracteres): queda en el historial.");
        OnlineLedgerPeriodEntity p = find(periodId);
        Plan x = evaluate(p, false).plans.stream().filter(y -> y.saleId.equals(req.saleId())).findFirst()
                .orElseThrow(() -> new BusinessException("La venta no pertenece al periodo."));
        String who = x.shipmentNumber != null ? x.shipmentNumber : "La venta #" + x.saleNumber;
        if (!x.actions.contains(req.action())) {
            throw new BusinessException("Ese arreglo ya no aplica a " + who + ". Vuelva a comparar. Estado: " + x.reason);
        }
        String detail;
        switch (req.action()) {
            case VOID_AND_EXCLUDE -> detail = voidAndExclude(x, who, reason);
            case MARK_REFUND -> {
                ReconCaseEntity c = caseRepository.findById(x.caseId).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + x.caseId));
                events.record(c.getId(), null, REFUND_EVENT, c.getStatus(), c.getStatus(), Map.of("motivo", reason, "periodo", p.getLabel()));
                detail = who + " queda fuera de la liquidación como reembolso pendiente (" + q(c.getReceivedAmount()) + "): " + reason;
            }
            case MARK_VOIDED -> {
                ledgerService.assertCanEdit();
                ledgerService.update(x.saleId, Map.of("accountingStatus", "ANULADO"));
                if (x.caseId != null) caseService.observe(x.caseId, "Marcada Anulado en el libro desde Liquidar: " + reason);
                detail = who + " marcada Anulado en el libro: " + reason;
            }
            default -> throw new BusinessException("Arreglo desconocido: " + req.action());
        }
        logService.recordPeriodEvent(p, OnlineLedgerLogEntity.LIQUIDATE, "Arreglo antes de liquidar · " + detail);
        return new ResolveResult(detail);
    }

    /** El pago de una venta devuelta o anulada fue un error: se anula la aplicación (y el fondo si se capturó a mano) y se excluye. */
    private String voidAndExclude(Plan x, String who, String reason) throws BusinessException, ResourceNotFoundException {
        ReconCaseEntity c = caseRepository.findById(x.caseId).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + x.caseId));
        String why = "Pago aplicado por error a una venta devuelta o anulada: " + reason;
        BigDecimal voided = BigDecimal.ZERO;
        int kept = 0;
        for (ReconReceiptApplicationEntity a : applicationRepository.findByCaseIdAndStatus(c.getId(), Recon.RowStatus.ACTIVE)) {
            receiptService.voidApplication(a.getId(), why);
            voided = voided.add(money(a.getAmountApplied()));
            ReconReceiptEntity r = receiptRepository.findById(a.getReceiptId()).orElse(null);
            if (r == null || r.getStatus() != Recon.RowStatus.ACTIVE) continue;
            boolean free = applicationRepository.findByReceiptIdAndStatus(r.getId(), Recon.RowStatus.ACTIVE).isEmpty();
            // Un fondo importado (transportista, banco, tarjeta) es dinero real: queda sin aplicar para asignarlo a la venta correcta.
            if (free && r.getImportFileId() == null) receiptService.voidReceipt(r.getId(), why);
            else kept++;
        }
        caseService.exclude(c.getId(), "Venta devuelta o anulada: " + reason);
        return who + ": se anuló el pago de " + q(voided) + " y se excluyó de la conciliación"
                + (kept > 0 ? "; " + kept + " fondo(s) importado(s) quedaron sin aplicar para asignarlos a la venta correcta" : "") + ". Motivo: " + reason;
    }

    /** Deja el caso como el libro. Devuelve lo que cambió (vacío si ya estaba igual). */
    private List<String> applyPlan(ReconCaseEntity c, Plan x, OnlineLedgerPeriodEntity p) throws BusinessException, ResourceNotFoundException {
        String why = "Igualado al libro contable, periodo «" + p.getLabel() + "»";
        List<String> changes = new ArrayList<>();
        if (x.include) {
            caseService.include(c.getId());
            changes.add("reincorporada");
        }
        if (x.exclude) {
            caseService.exclude(c.getId(), "El libro contable la tiene como " + x.ledgerStatus + " (periodo «" + p.getLabel() + "»)");
            changes.add("excluida: " + x.ledgerStatus);
            record(c, p, changes);
            return changes;
        }
        if (x.setExpected != null) {
            BigDecimal before = c.getExpectedAmount();
            caseService.overwriteExpectedAmount(c.getId(), x.setExpected, why);
            changes.add("cobrado " + q(before) + " → " + q(x.setExpected));
        }
        if (x.receiptAmount != null) {
            if (x.replaceExisting) {
                settlementService.replace(c.getId(), new ReplaceReceiptRequest(x.receiptAmount, false, x.receiptReference));
                changes.add("recibido → " + q(x.receiptAmount) + " (corregido)");
            } else {
                receiptService.recordForCase(c.getId(), new CaseReceiptRequest(null, x.receiptReference, x.receiptDate, x.receiptAmount, null));
                changes.add("recibido " + q(x.receiptAmount) + (x.receiptFromLedger ? " (depósito real del libro)" : " (igual a lo cobrado)"));
            }
        }
        if (x.setShipping != null) {
            ReconCaseEntity fresh = caseRepository.findById(c.getId()).orElseThrow();
            caseService.updateShippingCommission(c.getId(), x.setShipping, fresh.getCarrierCommissionRate());
            changes.add("envío real → " + q(x.setShipping));
        }
        record(c, p, changes);
        return changes;
    }

    private void record(ReconCaseEntity c, OnlineLedgerPeriodEntity p, List<String> changes) {
        ReconCaseEntity fresh = caseRepository.findById(c.getId()).orElse(c);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("periodo", p.getLabel());
        payload.put("cambios", changes.isEmpty() ? "ninguno: ya estaba igual al libro" : String.join("; ", changes));
        events.record(c.getId(), null, "LIBRO_APLICADO", fresh.getStatus(), fresh.getStatus(), payload);
    }

    // ───────── evaluación (compartida por preview y apply) ─────────

    /** Lo que se haría con una venta; {@code row} es lo que se muestra. */
    private static final class Plan {
        Long saleId;
        String saleNumber;
        String shipmentNumber;
        Long caseId;
        String state;
        String reason;
        String ledgerStatus;
        boolean include;
        boolean exclude;
        BigDecimal setExpected;
        BigDecimal receiptAmount;
        String receiptReference;
        LocalDate receiptDate;
        boolean receiptFromLedger;
        boolean replaceExisting;
        BigDecimal setShipping;
        /** Por qué no está lista, en código (regla R.. o PAGO_EN_DEVOLUCION / NOTA_ANULADO); null si no hay bloqueo. */
        String blockerCode;
        /** Arreglos que se pueden hacer desde la pantalla para esta venta. */
        List<String> actions = List.of();
        Row row;

        boolean selectable() {
            return LISTA.equals(state) || LISTA_CON_CAMBIOS.equals(state) || EXCLUIR.equals(state);
        }

        Row row() {
            return row;
        }
    }

    private static final class Evaluation {
        final List<Plan> plans = new ArrayList<>();
        int unreviewed;
        int differences;
        String closeBlocker;

        Summary summary() {
            int ready = 0, withChanges = 0, toExclude = 0, notReady = 0, settled = 0, na = 0;
            BigDecimal amount = BigDecimal.ZERO;
            for (Plan x : plans) {
                switch (x.state) {
                    case LISTA -> { ready++; amount = amount.add(money(x.row.reconExpected())); }
                    case LISTA_CON_CAMBIOS -> { withChanges++; amount = amount.add(money(x.row.ledgerTotal())); }
                    case EXCLUIR -> toExclude++;
                    case NO_LISTA -> notReady++;
                    case YA_LIQUIDADA -> settled++;
                    case NO_ENTRA, NO_DESPACHADA -> na++;
                    default -> { }
                }
            }
            int reviewed = (int) plans.stream().filter(x -> x.row.reviewed()).count();
            return new Summary(plans.size(), reviewed, unreviewed, ready, withChanges, toExclude, notReady, settled, na, differences, money(amount));
        }
    }

    private Evaluation evaluate(OnlineLedgerPeriodEntity p, boolean assumeEqual) throws BusinessException {
        LedgerResponse resp = ledgerService.search(p.getDateFrom(), p.getDateTo(), null);
        if (resp.isTruncated()) throw new BusinessException("El periodo tiene demasiadas ventas para compararlas de una vez. Use un periodo más corto.");
        List<LedgerRow> rows = resp.getRows();
        List<Long> saleIds = rows.stream().map(LedgerRow::getId).toList();

        Map<Long, ReconCaseEntity> cases = new HashMap<>();
        for (int i = 0; i < saleIds.size(); i += CHUNK) {
            for (ReconCaseEntity c : caseRepository.findByOnlineSaleIdIn(saleIds.subList(i, Math.min(saleIds.size(), i + CHUNK)))) {
                cases.put(c.getOnlineSaleId(), c);
            }
        }
        List<Long> caseIds = cases.values().stream().map(ReconCaseEntity::getId).toList();
        Map<Long, List<ReconReceiptApplicationEntity>> apps = new HashMap<>();
        Set<Long> withAdjustment = new HashSet<>();
        for (int i = 0; i < caseIds.size(); i += CHUNK) {
            List<Long> part = caseIds.subList(i, Math.min(caseIds.size(), i + CHUNK));
            for (ReconReceiptApplicationEntity a : applicationRepository.findByCaseIdInAndStatus(part, Recon.RowStatus.ACTIVE)) {
                apps.computeIfAbsent(a.getCaseId(), k -> new ArrayList<>()).add(a);
            }
            differenceRepository.findByCaseIdInAndStatus(part, Recon.DifferenceStatus.PROPOSED).forEach(d -> withAdjustment.add(d.getCaseId()));
            differenceRepository.findByCaseIdInAndStatus(part, Recon.DifferenceStatus.AUTHORIZED).forEach(d -> withAdjustment.add(d.getCaseId()));
        }
        List<Long> receiptIds = apps.values().stream().flatMap(List::stream).map(ReconReceiptApplicationEntity::getReceiptId).distinct().toList();
        Map<Long, ReconReceiptEntity> receipts = new HashMap<>();
        for (int i = 0; i < receiptIds.size(); i += CHUNK) {
            receiptRepository.findByIdIn(receiptIds.subList(i, Math.min(receiptIds.size(), i + CHUNK))).forEach(r -> receipts.put(r.getId(), r));
        }
        Set<Long> batchIds = cases.values().stream().map(ReconCaseEntity::getBatchId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> batchCodes = batchIds.isEmpty() ? Map.of()
                : batchRepository.findAllById(batchIds).stream().collect(Collectors.toMap(ReconBatchEntity::getId, ReconBatchEntity::getCode));

        // Las reglas del motor, con una sola carga: la vista previa debe rechazar lo mismo que rechaza la liquidación.
        Map<Long, CaseFacts> facts = recompute.factsForAll(cases.values().stream()
                .filter(c -> !c.isClosed() && c.getBatchId() == null).toList());
        Map<Long, String> refunds = refundNotes(caseIds);
        boolean ledgerEditable = !p.isClosed() && ledgerService.canEdit();

        Evaluation ev = new Evaluation();
        for (LedgerRow l : rows) {
            ReconCaseEntity c = cases.get(l.getId());
            Plan x = plan(l, c, c == null ? List.of() : apps.getOrDefault(c.getId(), List.of()), receipts,
                    c != null && withAdjustment.contains(c.getId()), c == null || c.getBatchId() == null ? null : batchCodes.get(c.getBatchId()), assumeEqual,
                    c == null ? null : facts.get(c.getId()), c == null ? null : refunds.get(c.getId()), ledgerEditable);
            if (!x.row.reviewed()) ev.unreviewed++;
            if (!x.row.diffs().isEmpty()) ev.differences++;
            ev.plans.add(x);
        }
        long notReady = ev.plans.stream().filter(x -> NO_LISTA.equals(x.state)).count();
        if (p.isClosed()) ev.closeBlocker = "La revisión ya está cerrada";
        else if (ev.unreviewed > 0) ev.closeBlocker = ev.unreviewed + " venta(s) sin marcar «Revisado» en el libro";
        else if (notReady > 0) ev.closeBlocker = notReady + " venta(s) que aún no cuadran con el libro";
        return ev;
    }

    /** Motivo de la última marca «reembolso pendiente» de cada caso. */
    private Map<Long, String> refundNotes(List<Long> caseIds) {
        Map<Long, String> out = new HashMap<>();
        for (int i = 0; i < caseIds.size(); i += CHUNK) {
            for (ReconEventEntity e : eventRepository.findByCaseIdInAndEventTypeOrderByIdDesc(caseIds.subList(i, Math.min(caseIds.size(), i + CHUNK)), REFUND_EVENT)) {
                out.putIfAbsent(e.getCaseId(), payloadText(e.getPayload(), "motivo"));
            }
        }
        return out;
    }

    private String payloadText(String json, String key) {
        if (blank(json)) return "";
        try {
            Object v = objectMapper.readValue(json, Map.class).get(key);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    /** La guía o la autorización trae una nota de anulación escrita a mano («ANULADO»), aunque la venta siga activa. */
    static boolean notedAsVoided(LedgerRow l) {
        String auth = !blank(l.getAccountingAuthorization()) ? l.getAccountingAuthorization() : l.getPaymentAuthorization();
        return mentionsVoid(l.getGuideNumber()) || mentionsVoid(auth);
    }

    private static boolean mentionsVoid(String v) {
        return v != null && !hasDigit(v) && v.toUpperCase(Locale.ROOT).contains("ANUL");
    }

    /**
     * Lo que dirían las reglas del motor después de igualar el caso al libro (cobrado del libro y, si aplica, el pago que se
     * registraría). Devuelve la primera falla que impide liquidar, o null si quedaría conciliada.
     */
    private RuleResult projectedBlocker(ReconCaseEntity c, CaseFacts f, Plan x, BigDecimal afterExpected) {
        List<ReceiptFact> projected = new ArrayList<>(x.receiptAmount != null && x.replaceExisting ? List.of() : f.receipts());
        if (x.receiptAmount != null) {
            projected.add(new ReceiptFact(null, ReconciliationReceiptService.inferSource(c), null, null, x.receiptReference, null,
                    x.receiptAmount, null, x.receiptDate));
        }
        CaseFacts after = new CaseFacts(f.origin(), f.saleStatus(), f.shipmentNumber(), f.guideNumber(), f.carrier(), f.paymentMethod(),
                f.paymentAuthorization(), f.saleDate(), f.dispatchedAt(), afterExpected, f.netAmount(), f.shippingCost(), f.itemsSubtotal(),
                f.currentSaleTotal(), f.invoiceTotal(), f.sellerInCatalog(), f.commissionRate(), f.registeredCommission(), projected,
                f.otherCasesWithSameGuide(), f.otherCasesWithSameReference());
        List<RuleResult> results = engine.evaluate(after);
        Recon.Status status = ReconciliationStatusResolver.resolve(results, false, c.isManualReconciled(), afterExpected, after.totalApplied(),
                !projected.isEmpty(), Optional.empty(), false);
        if (status.isReconciled() || status == Recon.Status.PENDIENTE_REVISION) return null;
        return results.stream().filter(RuleResult::criticalFailure).filter(r -> !MONEY_RULES.contains(r.code())).findFirst()
                .orElseGet(() -> results.stream().filter(RuleResult::criticalFailure).findFirst()
                        .orElse(new RuleResult("ESTADO", "Estado", Recon.Severity.CRITICAL, Recon.Outcome.FAIL, "Conciliada", status.name(),
                                "Quedaría en " + status.name() + ".", "Ábrala en el detalle")));
    }

    private Plan plan(LedgerRow l, ReconCaseEntity c, List<ReconReceiptApplicationEntity> apps, Map<Long, ReconReceiptEntity> receipts,
                      boolean hasAdjustment, String batchCode, boolean assumeEqual, CaseFacts facts, String refundNote, boolean ledgerEditable) {
        Plan x = new Plan();
        x.saleId = l.getId();
        x.saleNumber = l.getSaleNumber();
        x.shipmentNumber = l.getShipmentNumber();
        x.caseId = c == null ? null : c.getId();
        x.ledgerStatus = l.getStatus() == null ? "" : l.getStatus().toUpperCase(Locale.ROOT);
        boolean reviewed = Boolean.TRUE.equals(l.getReviewed());
        boolean ledgerExcluded = OnlineSaleLedgerService.isReturn(l.getStatus()) || OnlineSaleLedgerService.isVoided(l.getStatus());
        Map<String, Object> original = l.getSaleOriginal() == null ? Map.of() : l.getSaleOriginal();
        String saleStatus = original.get("status") != null ? String.valueOf(original.get("status")) : l.getStatus();
        BigDecimal saleTotal = original.get("totalAmount") instanceof BigDecimal b ? b : l.getTotalAmount();

        List<FieldDiff> diffs = new ArrayList<>();
        BigDecimal curExpected = c != null ? money(c.getExpectedAmount()) : money(saleTotal);
        BigDecimal curReceived = c != null ? money(c.getReceivedAmount()) : money(null);
        BigDecimal afterExpected = l.getTotalAmount() != null ? money(l.getTotalAmount()) : curExpected;
        BigDecimal lDeposit = l.getRealDepositAmount() != null && l.getRealDepositAmount().signum() > 0 ? money(l.getRealDepositAmount()) : null;
        BigDecimal afterDifference = null;
        boolean fixableDiff = false;

        if (c != null && c.isClosed() || c != null && c.getBatchId() != null) {
            x.state = YA_LIQUIDADA;
            if (!sameMoney(afterExpected, curExpected)) diffs.add(new FieldDiff("TOTAL", "Cobrado", q(afterExpected), q(curExpected), false, "El libro cambió después de liquidar"));
            if (lDeposit != null && !sameMoney(lDeposit, curReceived)) diffs.add(new FieldDiff("RECIBIDO", "Recibido", q(lDeposit), q(curReceived), false, "El libro cambió después de liquidar"));
            x.reason = "Ya liquidada" + (batchCode == null ? "" : " en el lote " + batchCode)
                    + (diffs.isEmpty() ? "" : "; el libro difiere de lo liquidado (anule el lote si debe corregirse)");
        } else if (ledgerExcluded) {
            if (c == null || c.getExcludedReason() != null) {
                x.state = NO_ENTRA;
                x.reason = "El libro la tiene como " + x.ledgerStatus + ": no se liquida";
            } else if (!apps.isEmpty() && refundNote != null) {
                x.state = NO_ENTRA;
                x.reason = "Reembolso pendiente: queda fuera de la liquidación" + (blank(refundNote) ? "" : " (" + refundNote + ")");
            } else if (!apps.isEmpty()) {
                x.state = NO_LISTA;
                x.blockerCode = "PAGO_EN_DEVOLUCION";
                x.actions = List.of(VOID_AND_EXCLUDE, MARK_REFUND);
                x.reason = "El libro la tiene como " + x.ledgerStatus + " pero tiene " + q(curReceived) + " aplicados. Si el pago fue un error, "
                        + "anúlelo y exclúyala; si el dinero sí entró, déjela como reembolso pendiente";
                diffs.add(new FieldDiff("ESTADO", "Estado", x.ledgerStatus, "Activa con pagos", false, x.reason));
            } else {
                x.state = EXCLUIR;
                x.exclude = true;
                x.reason = "El libro la tiene como " + x.ledgerStatus + ": se excluye de la conciliación";
                diffs.add(new FieldDiff("ESTADO", "Estado", x.ledgerStatus, "Activa", true, "Se excluye"));
            }
        } else if (c == null && !TRACKED.contains(saleStatus == null ? "" : saleStatus.toUpperCase(Locale.ROOT))) {
            x.state = NO_DESPACHADA;
            x.reason = "La venta está en estado " + (saleStatus == null ? "—" : saleStatus) + ": aún no se concilia";
        } else if (c != null && c.getExcludedReason() != null && !c.getExcludedReason().startsWith(AUTO_EXCLUDE_PREFIX)) {
            x.state = NO_LISTA;
            x.reason = "Está excluida a mano (" + c.getExcludedReason() + "): reincorpórela en el detalle si corresponde";
        } else {
            if (c != null && c.getExcludedReason() != null) {
                x.include = true;
                diffs.add(new FieldDiff("ESTADO", "Estado", x.ledgerStatus, "Excluida", true, "Se reincorpora"));
            }
            String blocker = null;
            if (!sameMoney(afterExpected, curExpected)) {
                diffs.add(new FieldDiff("TOTAL", "Cobrado (total con envío)", q(afterExpected), q(curExpected), !hasAdjustment,
                        hasAdjustment ? "Tiene un ajuste propuesto o autorizado" : "Se sobrescribe con el libro"));
                if (hasAdjustment) blocker = "Cambió el monto cobrado pero tiene un ajuste propuesto o autorizado: recházelo o reabra el caso";
                else x.setExpected = afterExpected;
            }
            BigDecimal afterReceived;
            if (lDeposit != null) {
                afterReceived = lDeposit;
                if (!apps.isEmpty() && sameMoney(curReceived, lDeposit)) {
                    // ya coincide
                } else {
                    boolean single = apps.size() == 1 && receipts.get(apps.get(0).getReceiptId()) != null
                            && receipts.get(apps.get(0).getReceiptId()).getImportFileId() == null;
                    String note;
                    boolean fixable = true;
                    if (apps.isEmpty()) {
                        note = "Se registra el depósito real del libro";
                    } else if (single) {
                        x.replaceExisting = true;
                        note = "Se corrige el pago registrado";
                    } else {
                        fixable = false;
                        note = "El pago viene de un archivo importado o son varios: corríjalo en el detalle";
                        if (blocker == null) blocker = note;
                    }
                    diffs.add(new FieldDiff("RECIBIDO", "Recibido (depósito real)", q(lDeposit), apps.isEmpty() ? "Sin pago" : q(curReceived), fixable, note));
                    if (fixable) {
                        x.receiptAmount = lDeposit;
                        x.receiptFromLedger = true;
                    }
                }
            } else if (!apps.isEmpty()) {
                afterReceived = curReceived;
            } else if (assumeEqual && afterExpected.signum() > 0) {
                afterReceived = afterExpected;
                x.receiptAmount = afterExpected;
                diffs.add(new FieldDiff("RECIBIDO", "Recibido", "Sin depósito anotado", "Sin pago", true, "Se toma igual a lo cobrado"));
            } else {
                afterReceived = null;
            }
            if (x.receiptAmount != null) {
                x.receiptReference = blank(l.getDepositSlipNumber()) ? null : l.getDepositSlipNumber().trim();
                LocalDate d = l.getLiquidationDate();
                x.receiptDate = d != null && !d.isAfter(GuatemalaDateTime.today()) ? d : null;
            }
            BigDecimal lShip = l.getRealShippingCost();
            BigDecimal cShip = c == null ? null : c.getRealShipping();
            if (lShip != null && (cShip == null || !sameMoney(lShip, cShip))) {
                diffs.add(new FieldDiff("ENVIO_REAL", "Envío real", q(lShip), cShip == null ? "—" : q(cShip), true, "Solo registro: no cambia el cuadre"));
                x.setShipping = money(lShip);
            }
            if (blocker != null) {
                x.state = NO_LISTA;
                x.reason = blocker;
            } else if (afterReceived == null) {
                x.state = NO_LISTA;
                x.reason = "Falta el pago: el libro no anota depósito real y la conciliación no tiene pago registrado";
            } else if (afterExpected.signum() == 0 && afterReceived.signum() == 0) {
                x.state = NO_LISTA;
                x.reason = "Venta de Q0.00: no hay pago que registrar";
            } else {
                afterDifference = afterReceived.subtract(afterExpected);
                if (afterDifference.signum() != 0) {
                    x.state = NO_LISTA;
                    x.reason = "No cuadra con el libro: recibido " + q(afterReceived) + ", cobrado " + q(afterExpected)
                            + " (" + (afterDifference.signum() < 0 ? "falta " : "sobra ") + q(afterDifference.abs()) + ")";
                } else {
                    fixableDiff = diffs.stream().anyMatch(FieldDiff::fixable);
                    x.state = fixableDiff ? LISTA_CON_CAMBIOS : LISTA;
                    x.reason = fixableDiff ? "Cuadra después de igualarla al libro" : "Cuadra tal cual";
                    // El monto cuadra, pero la liquidación vuelve a pasar todas las reglas: si alguna crítica falla, aquí ya no sale lista.
                    RuleResult r = c == null || facts == null ? null : projectedBlocker(c, facts, x, afterExpected);
                    if (r != null) {
                        x.state = NO_LISTA;
                        x.blockerCode = r.code();
                        x.reason = r.code() + " · " + r.message() + (blank(r.found()) || "—".equals(r.found()) ? "" : " (" + r.found() + ")")
                                + (blank(r.action()) ? "" : ". " + r.action());
                        if ("R15".equals(r.code()) || "R01".equals(r.code()) && !apps.isEmpty()) {
                            x.actions = refundNote != null ? List.of() : List.of(VOID_AND_EXCLUDE, MARK_REFUND);
                        }
                    }
                }
            }
        }

        // «ANULADO» escrito en la guía o la autorización de una venta activa: no se liquida como vendida sin que alguien lo decida.
        boolean active = !ledgerExcluded && !YA_LIQUIDADA.equals(x.state) && !NO_ENTRA.equals(x.state) && !NO_DESPACHADA.equals(x.state);
        if (active && notedAsVoided(l)) {
            if (x.selectable()) {
                x.state = NO_LISTA;
                x.blockerCode = "NOTA_ANULADO";
                x.reason = "La guía o la autorización dice «ANULADO» pero la venta está " + x.ledgerStatus
                        + ": márquela Anulado en el libro, o corrija la nota si sí se vendió";
            }
            if (ledgerEditable) {
                List<String> acts = new ArrayList<>(x.actions);
                acts.add(MARK_VOIDED);
                x.actions = acts;
            }
        }

        // Datos que la conciliación toma de la venta: solo informativos.
        if (c != null) {
            info(diffs, "GUIA", "Guía", l.getGuideNumber(), c.getGuideNumber(), true);
            info(diffs, "ENVL", "ENVL", l.getShipmentNumber(), c.getShipmentNumber(), true);
            info(diffs, "TRANSPORTE", "Transportista", l.getCarrier(), c.getCarrier(), false);
            info(diffs, "VENDEDOR", "Vendedor", l.getSalesperson(), c.getSalesperson(), true);
        }

        if (!reviewed) {
            x.state = SIN_REVISAR;
            x.reason = "Sin marcar «Revisado» en el libro";
            x.include = false;
            x.exclude = false;
            x.setExpected = null;
            x.receiptAmount = null;
            x.setShipping = null;
        }

        x.row = new Row(l.getId(), l.getSaleNumber(), l.getShipmentNumber(), l.getCustomerName(), l.getSaleDate(), l.getCarrier(), l.getPaymentMethodDisplay(),
                reviewed, x.caseId, c == null ? null : c.getStatus().name(), batchCode, x.state, x.reason, x.selectable(),
                l.getTotalAmount(), c == null ? saleTotal : c.getExpectedAmount(), l.getRealDepositAmount(), c == null ? null : c.getReceivedAmount(),
                afterDifference, diffs, x.blockerCode, x.actions);
        return x;
    }

    /** Diferencia solo informativa (la conciliación la toma de la venta, que el libro no modifica). */
    private static void info(List<FieldDiff> diffs, String key, String label, String ledger, String recon, boolean byNorm) {
        if (blank(ledger)) return;
        boolean same = byNorm ? norm(ledger).equals(norm(recon)) : carrierKey(ledger).equals(carrierKey(recon));
        if (!same) {
            diffs.add(new FieldDiff(key, label, ledger.trim(), blank(recon) ? "—" : recon.trim(), false,
                    "Solo informativo: la conciliación lo toma de la venta, no del libro"));
        }
    }

    private OnlineLedgerPeriodEntity find(Long id) throws ResourceNotFoundException {
        return periodRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Periodo de revisión", id));
    }
}
