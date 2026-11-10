package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.service.reconciliation.CaseFacts.ReceiptFact;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/**
 * Vuelve a evaluar casos: carga lo necesario en lote, corre las reglas, guarda sus resultados y actualiza
 * montos, estado y problema principal. Los montos recibidos, la diferencia y el saldo se calculan siempre a
 * partir de las aplicaciones activas y del ajuste autorizado; nunca se editan a mano.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationRecomputeService {

    private static final int CHUNK = 500;

    private final ReconCaseRepository caseRepository;
    private final ReconSellerRepository sellerRepository;
    private final ReconReceiptRepository receiptRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconCheckResultRepository checkRepository;
    private final ReconDifferenceRepository differenceRepository;
    private final ReconCommissionLineRepository commissionRepository;
    private final OnlineSaleRepository saleRepository;
    private final OnlineSaleItemRepository itemRepository;
    private final TaxInvoiceRepository invoiceRepository;
    private final ReconciliationRuleEngine engine;
    private final ReconEventService events;
    private final ReconciliationProperties props;
    private final JdbcTemplate jdbc;

    @Transactional
    public void recompute(ReconCaseEntity c) {
        recomputeAll(List.of(c));
    }

    @Transactional
    public void recomputeAll(Collection<ReconCaseEntity> cases) {
        recomputeAll(cases, true);
    }

    /** @param recordEvents false al crear casos: el evento de creación ya dice el estado inicial. */
    @Transactional
    public void recomputeAll(Collection<ReconCaseEntity> cases, boolean recordEvents) {
        List<ReconCaseEntity> open = cases.stream().filter(c -> !c.isClosed()).toList();
        if (open.isEmpty()) return;
        Ctx ctx = load(open);
        for (ReconCaseEntity c : open) {
            recomputeOne(c, ctx, recordEvents);
        }
    }

    /** Resultados de las reglas de un caso abierto, calculados al momento (no se guardan hasta cerrar el lote). */
    @Transactional(readOnly = true)
    public List<RuleResult> evaluate(ReconCaseEntity c) {
        return engine.evaluate(facts(c, load(List.of(c))));
    }

    /**
     * Guarda la foto de las reglas de cada caso (se hace al cerrar un lote, para poder reconstruir qué se verificó).
     * Usa inserciones por lote: son 17 filas por caso.
     */
    @Transactional
    public void persistChecks(Collection<ReconCaseEntity> cases) {
        if (cases.isEmpty()) return;
        Ctx ctx = load(cases);
        long runId = System.currentTimeMillis();
        List<Object[]> rows = new ArrayList<>();
        java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
        for (ReconCaseEntity c : cases) {
            for (RuleResult r : engine.evaluate(facts(c, ctx))) {
                rows.add(new Object[]{c.getId(), runId, r.code(), r.severity().name(), r.outcome().name(), cut(r.expected(), 200),
                        cut(r.found(), 200), cut(r.message(), 500), cut(r.action(), 300), now});
            }
        }
        for (ReconCaseEntity c : cases) checkRepository.deleteByCaseId(c.getId());
        checkRepository.flush();
        jdbc.batchUpdate("insert into recon_check_result (case_id, run_id, rule_code, severity, outcome, expected_value, found_value, message, "
                + "recommended_action, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    /** Hechos de un caso tal como los ve el motor de reglas (también los usa el detalle). */
    @Transactional(readOnly = true)
    public CaseFacts factsFor(ReconCaseEntity c) {
        Ctx ctx = load(List.of(c));
        return facts(c, ctx);
    }

    private void recomputeOne(ReconCaseEntity c, Ctx ctx, boolean recordEvents) {
        OnlineSaleEntity sale = ctx.sales.get(c.getOnlineSaleId());
        if (sale != null) refreshNonMoneySnapshot(c, sale, ctx);

        CaseFacts facts = facts(c, ctx);
        List<RuleResult> results = engine.evaluate(facts);
        boolean hasReceipts = !facts.receipts().isEmpty();
        BigDecimal expected = money(c.getExpectedAmount());
        BigDecimal received = facts.totalApplied();

        ReconDifferenceEntity adjustment = ctx.adjustments.get(c.getId());
        boolean covers = adjustment != null && adjustment.getStatus() == Recon.DifferenceStatus.AUTHORIZED
                && expected.compareTo(received) > 0 && sameMoney(adjustment.getAmount(), expected.subtract(received));
        BigDecimal authorized = covers ? money(adjustment.getAmount()) : money(BigDecimal.ZERO);

        Recon.Status previous = c.getStatus();
        Recon.Status status = ReconciliationStatusResolver.resolve(results, c.getExcludedReason() != null, c.isManualReconciled(),
                expected, received, hasReceipts, Optional.ofNullable(adjustment).map(ReconDifferenceEntity::getStatus), covers);

        c.setReceivedAmount(received);
        c.setDifference(hasReceipts ? received.subtract(expected) : money(BigDecimal.ZERO));
        c.setAuthorizedAdjustment(authorized);
        c.setPendingBalance(status == Recon.Status.EXCLUIDO ? money(BigDecimal.ZERO) : expected.subtract(received).subtract(authorized));
        c.setStatus(status);
        c.setCriticalCount((int) results.stream().filter(RuleResult::criticalFailure).count());
        c.setWarningCount((int) results.stream().filter(r -> r.failed() && r.severity() == Recon.Severity.WARNING).count());
        long days = ChronoUnit.DAYS.between(c.getSaleDate(), GuatemalaDateTime.today());
        c.setProblemType(ReconciliationStatusResolver.primaryProblem(status, results, hasReceipts, expected, days)
                .filter(p -> p != Recon.DifferenceType.VENTA_SIN_ENVIO || days > props.getMissingFundsAfterDays())
                .orElse(null));
        caseRepository.save(c);

        if (recordEvents && previous != status) {
            events.record(c.getId(), null, "ESTADO_CAMBIO", previous, status, Map.of("criticas", c.getCriticalCount(), "advertencias", c.getWarningCount()));
        }
    }

    /** Guía, transportista, forma de pago, vendedor y fechas se refrescan solos; los montos solo con una acción explícita. */
    private void refreshNonMoneySnapshot(ReconCaseEntity c, OnlineSaleEntity sale, Ctx ctx) {
        String oldGuide = c.getGuideNumber();
        c.setShipmentNumber(sale.getShipmentNumber());
        c.setSaleNumber(sale.getSaleNumber());
        c.setGuideNumber(sale.getGuideNumber());
        c.setCarrier(sale.getShippingCarrier());
        c.setPaymentMethod(sale.getPaymentMethod());
        c.setSalesperson(sale.getSalesperson());
        c.setDispatchedAt(sale.getDispatchedAt());
        c.setSourceUpdatedAt(sale.getUpdatedAt());
        if (sale.getSalesperson() != null) {
            c.setSellerId(ctx.sellersByName.get(sale.getSalesperson().trim()) == null ? null
                    : ctx.sellersByName.get(sale.getSalesperson().trim()).getId());
        }
        if (!Objects.equals(oldGuide, c.getGuideNumber())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("antes", oldGuide);
            payload.put("ahora", c.getGuideNumber());
            events.record(c.getId(), null, "GUIA_ACTUALIZADA", null, null, payload);
        }
    }

    private CaseFacts facts(ReconCaseEntity c, Ctx ctx) {
        OnlineSaleEntity sale = ctx.sales.get(c.getOnlineSaleId());
        List<ReceiptFact> receipts = new ArrayList<>();
        for (ReconReceiptApplicationEntity a : ctx.apps.getOrDefault(c.getId(), List.of())) {
            ReconReceiptEntity r = ctx.receipts.get(a.getReceiptId());
            if (r == null) continue;
            receipts.add(new ReceiptFact(r.getId(), r.getSourceType(), r.getCarrier(), r.getGuideNumber(), r.getReference(),
                    r.getAuthorizationNumber(), a.getAmountApplied(), r.getShippingAmount(), r.getReceivedDate()));
        }
        BigDecimal itemsSubtotal = null;
        List<OnlineSaleItemEntity> items = ctx.items.get(c.getOnlineSaleId());
        if (items != null && !items.isEmpty()) {
            itemsSubtotal = items.stream().map(i -> i.getSubtotal() == null ? BigDecimal.ZERO : i.getSubtotal())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal invoiceTotal = sale == null || sale.getInvoiceId() == null ? null : ctx.invoiceTotals.get(sale.getInvoiceId());
        ReconSellerEntity seller = c.getSellerId() == null ? null : ctx.sellersById.get(c.getSellerId());
        ReconCommissionLineEntity line = ctx.commissions.get(c.getId());

        List<String> sameGuide = new ArrayList<>();
        if (!blank(c.getGuideNumber())) {
            for (ReconCaseEntity other : ctx.casesByGuide.getOrDefault(norm(c.getGuideNumber()), List.of())) {
                if (!other.getId().equals(c.getId())) sameGuide.add(other.getShipmentNumber() == null ? "caso " + other.getId() : other.getShipmentNumber());
            }
        }
        List<String> sameReference = new ArrayList<>();
        String auth = sale == null ? null : sale.getPaymentAuthorization();
        if (!blank(auth)) {
            for (OnlineSaleEntity other : saleRepository.findByPaymentAuthorizationIgnoreCase(auth.trim())) {
                if (!other.getId().equals(c.getOnlineSaleId())) sameReference.add(other.getShipmentNumber() == null ? "venta " + other.getId() : other.getShipmentNumber());
            }
        }
        return new CaseFacts(c.getOrigin(), sale == null ? "DESCONOCIDO" : sale.getStatus(), c.getShipmentNumber(), c.getGuideNumber(),
                c.getCarrier(), c.getPaymentMethod(), auth, c.getSaleDate(), c.getDispatchedAt(), c.getExpectedAmount(),
                sale == null ? c.getExpectedAmount() : sale.getNetAmount(), sale == null ? BigDecimal.ZERO : sale.getShippingCost(),
                itemsSubtotal, sale == null ? null : sale.getTotalAmount(), invoiceTotal,
                seller != null && seller.isActive(), props.getCommissionRate(), line == null ? null : line.getCommissionAmount(),
                receipts, sameGuide, sameReference);
    }

    // ───────── carga en lote ─────────

    private record Ctx(Map<Long, OnlineSaleEntity> sales, Map<Long, List<OnlineSaleItemEntity>> items,
                       Map<Long, List<ReconReceiptApplicationEntity>> apps, Map<Long, ReconReceiptEntity> receipts,
                       Map<Long, ReconCommissionLineEntity> commissions, Map<Long, BigDecimal> invoiceTotals,
                       Map<String, List<ReconCaseEntity>> casesByGuide, Map<Long, ReconDifferenceEntity> adjustments,
                       Map<Long, ReconSellerEntity> sellersById, Map<String, ReconSellerEntity> sellersByName) {
    }

    private Ctx load(Collection<ReconCaseEntity> cases) {
        List<Long> caseIds = cases.stream().map(ReconCaseEntity::getId).toList();
        List<Long> saleIds = cases.stream().map(ReconCaseEntity::getOnlineSaleId).toList();

        Map<Long, OnlineSaleEntity> sales = new HashMap<>();
        Map<Long, List<OnlineSaleItemEntity>> items = new HashMap<>();
        for (List<Long> part : chunks(saleIds)) {
            saleRepository.findAllById(part).forEach(s -> sales.put(s.getId(), s));
            for (OnlineSaleItemEntity i : itemRepository.findByOnlineSaleIdInOrderByIdAsc(part)) {
                items.computeIfAbsent(i.getOnlineSaleId(), k -> new ArrayList<>()).add(i);
            }
        }
        Map<Long, List<ReconReceiptApplicationEntity>> apps = new HashMap<>();
        Map<Long, ReconCommissionLineEntity> commissions = new HashMap<>();
        Map<Long, ReconDifferenceEntity> adjustments = new HashMap<>();
        for (List<Long> part : chunks(caseIds)) {
            for (ReconReceiptApplicationEntity a : applicationRepository.findByCaseIdInAndStatus(part, Recon.RowStatus.ACTIVE)) {
                apps.computeIfAbsent(a.getCaseId(), k -> new ArrayList<>()).add(a);
            }
            commissionRepository.findByCaseIdIn(part).forEach(l -> commissions.put(l.getCaseId(), l));
            List<ReconDifferenceEntity> adj = new ArrayList<>(differenceRepository.findByCaseIdInAndStatus(part, Recon.DifferenceStatus.PROPOSED));
            adj.addAll(differenceRepository.findByCaseIdInAndStatus(part, Recon.DifferenceStatus.AUTHORIZED));
            for (ReconDifferenceEntity d : adj) {
                adjustments.merge(d.getCaseId(), d, (x, y) -> x.getId() > y.getId() ? x : y);
            }
        }
        Map<Long, ReconReceiptEntity> receipts = new HashMap<>();
        List<Long> receiptIds = apps.values().stream().flatMap(List::stream).map(ReconReceiptApplicationEntity::getReceiptId).distinct().toList();
        for (List<Long> part : chunks(receiptIds)) {
            receiptRepository.findByIdIn(part).forEach(r -> receipts.put(r.getId(), r));
        }
        Map<Long, BigDecimal> invoiceTotals = new HashMap<>();
        List<Long> invoiceIds = sales.values().stream().map(OnlineSaleEntity::getInvoiceId).filter(Objects::nonNull).distinct().toList();
        for (List<Long> part : chunks(invoiceIds)) {
            for (TaxInvoiceEntity t : invoiceRepository.findAllById(part)) invoiceTotals.put(t.getId(), t.getTotalAmount());
        }
        Map<String, List<ReconCaseEntity>> byGuide = new HashMap<>();
        List<String> guides = cases.stream().map(ReconCaseEntity::getGuideNumber).filter(g -> !blank(g)).distinct().toList();
        for (List<String> part : chunks(guides)) {
            for (ReconCaseEntity o : caseRepository.findByGuideNumberIn(part)) {
                byGuide.computeIfAbsent(norm(o.getGuideNumber()), k -> new ArrayList<>()).add(o);
            }
        }
        List<ReconSellerEntity> sellers = sellerRepository.findAll();
        return new Ctx(sales, items, apps, receipts, commissions, invoiceTotals, byGuide, adjustments,
                sellers.stream().collect(Collectors.toMap(ReconSellerEntity::getId, s -> s)),
                sellers.stream().collect(Collectors.toMap(s -> s.getName().trim(), s -> s, (a, b) -> a)));
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        return out;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
