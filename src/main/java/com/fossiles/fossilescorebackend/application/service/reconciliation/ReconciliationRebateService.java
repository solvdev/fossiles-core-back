package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconReceiptApplicationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconReceiptEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconCaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconReceiptApplicationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/**
 * Ventas devueltas, anuladas o canceladas: no son una alerta, son una rebaja. Su caso se rebaja a Q0 (los pagos aplicados se
 * anulan solos y queda constancia en el historial), sale de la bandeja de diferencias y entra al lote de liquidación de su mes
 * como una fila «Devuelta»/«Anulada» con lo que era y lo que queda (Q0).
 */
@Service
@RequiredArgsConstructor
public class ReconciliationRebateService {

    private final ReconCaseRepository caseRepository;
    private final OnlineSaleRepository saleRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconReceiptRepository receiptRepository;
    private final ReconciliationLedgerService ledger;
    private final ReconciliationRecomputeService recompute;
    private final ReconEventService events;
    private final ReconActor actor;

    /** Rebaja los casos abiertos (sin lote ni exclusión) cuya venta ya está devuelta, anulada o cancelada. Devuelve cuántos. */
    @Transactional
    public int rebateClosedSales(Collection<ReconCaseEntity> cases) {
        List<ReconCaseEntity> open = cases.stream()
                .filter(c -> !c.isClosed() && c.getBatchId() == null && c.getExcludedReason() == null).toList();
        if (open.isEmpty()) return 0;
        Map<Long, OnlineSaleEntity> sales = saleRepository.findAllById(open.stream().map(ReconCaseEntity::getOnlineSaleId).toList()).stream()
                .collect(Collectors.toMap(OnlineSaleEntity::getId, s -> s));
        int n = 0;
        for (ReconCaseEntity c : open) {
            OnlineSaleEntity sale = sales.get(c.getOnlineSaleId());
            if (sale != null && sale.getStatus() != null && CLOSED_SALE_STATUS.contains(sale.getStatus().toUpperCase(Locale.ROOT))) {
                rebate(c, sale.getStatus().toUpperCase(Locale.ROOT), null);
                n++;
            }
        }
        return n;
    }

    /**
     * Rebaja un caso a Q0: anula sus pagos (y el fondo si se capturó a mano; uno importado queda libre para la venta correcta),
     * reversa el cargo y lo deja excluido con el estado de la venta, que es lo que lo marca como rebaja.
     */
    @Transactional
    public void rebate(ReconCaseEntity c, String saleStatus, String note) {
        if (c.isClosed() || c.getBatchId() != null) return;
        String status = blank(saleStatus) ? "DEVOLUCION" : saleStatus.trim().toUpperCase(Locale.ROOT);
        String why = "Venta en " + status + ": el pago se anula automáticamente (rebaja a Q0)";
        LocalDateTime now = LocalDateTime.now();
        Long userId = actor.id();
        BigDecimal voided = BigDecimal.ZERO;
        for (ReconReceiptApplicationEntity a : applicationRepository.findByCaseIdAndStatus(c.getId(), Recon.RowStatus.ACTIVE)) {
            a.setStatus(Recon.RowStatus.VOID);
            a.setVoidReason(why);
            a.setVoidedAt(now);
            a.setVoidedBy(userId);
            applicationRepository.save(a);
            voided = voided.add(money(a.getAmountApplied()));
            events.record(c.getId(), null, "APLICACION_ANULADA", c.getStatus(), c.getStatus(),
                    Map.of("fondo", a.getReceiptId(), "monto", money(a.getAmountApplied()).toPlainString(), "motivo", why, "automatico", true));
            ReconReceiptEntity r = receiptRepository.findById(a.getReceiptId()).orElse(null);
            if (r != null && r.getStatus() == Recon.RowStatus.ACTIVE && r.getImportFileId() == null
                    && applicationRepository.findByReceiptIdAndStatus(r.getId(), Recon.RowStatus.ACTIVE).isEmpty()) {
                r.setStatus(Recon.RowStatus.VOID);
                r.setVoidReason(why);
                r.setVoidedAt(now);
                r.setVoidedBy(userId);
                receiptRepository.save(r);
                events.record(null, null, "FONDO_ANULADO", null, null, Map.of("fondo", r.getId(), "motivo", why, "automatico", true));
            }
        }
        applicationRepository.flush();
        Recon.Status from = c.getStatus();
        String reason = SALE_STATE_EXCLUSION_PREFIX + status + (blank(note) ? "" : " · " + note.trim());
        c.setExcludedReason(reason.length() > 300 ? reason.substring(0, 300) : reason);
        c.setUpdatedBy(userId);
        ledger.reverseCharges(c, "Rebaja: venta en " + status);
        recompute.recompute(c);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("estado", status);
        payload.put("era", money(c.getExpectedAmount()).toPlainString());
        payload.put("pagoAnulado", voided.toPlainString());
        if (!blank(note)) payload.put("nota", note.trim());
        events.record(c.getId(), null, "REBAJADO", from, c.getStatus(), payload);
    }

    /**
     * Para armar un lote: primero rebaja lo devuelto/anulado de esos meses que siga abierto y devuelve las rebajas sin lote, que
     * entran al lote junto con lo conciliado (así el reporte final dice qué se devolvió o anuló).
     */
    @Transactional
    public List<ReconCaseEntity> rebatesForMonths(LocalDate anyFrom, LocalDate anyTo) {
        LocalDate from = anyFrom.withDayOfMonth(1);
        LocalDate to = anyTo.withDayOfMonth(anyTo.lengthOfMonth());
        List<ReconCaseEntity> inRange = caseRepository.findBySaleDateBetween(from, to);
        rebateClosedSales(inRange);
        return inRange.stream().filter(c -> !c.isClosed() && c.getBatchId() == null && isSaleStateRebate(c.getExcludedReason())).toList();
    }
}
