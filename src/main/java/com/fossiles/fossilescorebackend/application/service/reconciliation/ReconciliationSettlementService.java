package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconCaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconReceiptApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/**
 * Los dos atajos de la pantalla de liquidación. No cambian ninguna regla: solo juntan pasos que ya existían.
 * <ul>
 *   <li>{@link #recordMany}: guarda los pagos de varias ventas de una vez. Cada pago pasa por
 *       {@link ReconciliationReceiptService#recordForCase} (misma validación y una transacción por pago), así que uno
 *       que falla no frena a los demás. «Recibido = cobrado» registra exactamente el monto cobrado de una venta sin pagos.</li>
 *   <li>{@link #settle}: acepta los avisos de las ventas que cuadran al centavo, crea el lote con los casos elegidos y lo cierra.
 *       Todo o nada. Una venta con diferencia o con una falla crítica nunca entra.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReconciliationSettlementService {

    private final ReconCaseRepository caseRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconciliationReceiptService receiptService;
    private final ReconciliationCaseService caseService;
    private final ReconciliationBatchService batchService;
    private final ReconciliationRecomputeService recompute;

    public BulkReceiptResult recordMany(List<BulkReceiptItem> items) throws BusinessException {
        if (items == null || items.isEmpty()) throw new BusinessException("No hay pagos por guardar.");
        if (items.size() > 500) throw new BusinessException("Guarde como máximo 500 pagos a la vez.");
        List<BulkReceiptRow> rows = new ArrayList<>();
        int saved = 0;
        for (BulkReceiptItem item : items) {
            ReconCaseEntity c = item == null || item.caseId() == null ? null : caseRepository.findById(item.caseId()).orElse(null);
            String label = c == null ? null : c.getShipmentNumber();
            try {
                if (c == null) throw new BusinessException("La venta no existe.");
                var amount = item.amount();
                if (item.matchExpected()) {
                    if (!applicationRepository.findByCaseIdAndStatus(c.getId(), Recon.RowStatus.ACTIVE).isEmpty()) {
                        throw new BusinessException("Ya tiene un pago registrado. Si hay que corregirlo, ábrala y anule el pago anterior.");
                    }
                    amount = money(c.getExpectedAmount());
                }
                QuickReceiptResult r = receiptService.recordForCase(c.getId(), new CaseReceiptRequest(null, item.reference(), null, amount, null));
                rows.add(new BulkReceiptRow(c.getId(), label, true, r.message(), r.received(), r.difference(), r.status()));
                saved++;
            } catch (BusinessException | ResourceNotFoundException e) {
                rows.add(new BulkReceiptRow(item == null ? null : item.caseId(), label, false, e.getMessage(), null, null, null));
            }
        }
        return new BulkReceiptResult(saved, rows.size() - saved, rows);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettleResult settle(List<Long> caseIds, String idempotencyKey) throws BusinessException, ResourceNotFoundException {
        if (caseIds == null || caseIds.isEmpty()) throw new BusinessException("Elija las ventas por liquidar.");
        Optional<Long> already = batchService.findByIdempotencyKey(idempotencyKey);
        if (already.isPresent()) return new SettleResult(batchService.close(already.get(), idempotencyKey), 0, List.of());

        List<ReconCaseEntity> eligible = new ArrayList<>();
        List<InvalidCaseRef> skipped = new ArrayList<>();
        int accepted = 0;
        for (Long id : new LinkedHashSet<>(caseIds)) {
            ReconCaseEntity c = caseRepository.findById(id).orElse(null);
            if (c == null) {
                skipped.add(new InvalidCaseRef(id, null, "No existe"));
                continue;
            }
            if (c.getBatchId() != null || c.isClosed()) {
                skipped.add(new InvalidCaseRef(id, c.getShipmentNumber(), "Ya está en un lote"));
                continue;
            }
            recompute.recompute(c);
            if (c.getStatus() == Recon.Status.PENDIENTE_REVISION && money(c.getDifference()).signum() == 0) {
                // El monto cuadra al centavo y solo hay avisos: entra a la liquidación y los avisos quedan en el historial.
                String codes = recompute.evaluate(c).stream().filter(RuleResult::failed).map(RuleResult::code).collect(Collectors.joining(", "));
                caseService.reconcile(c.getId(), "Liquidado con avisos a la vista" + (codes.isEmpty() ? "" : ": " + codes));
                accepted++;
            }
            if (c.getStatus().isReconciled() && money(c.getPendingBalance()).signum() == 0) {
                eligible.add(c);
            } else {
                skipped.add(new InvalidCaseRef(id, c.getShipmentNumber(), reasonNotReady(c)));
            }
        }
        if (eligible.isEmpty()) {
            throw new BusinessException("Ninguna de las ventas elegidas está lista para liquidar. "
                    + skipped.stream().limit(5).map(s -> (s.shipmentNumber() == null ? "caso " + s.caseId() : s.shipmentNumber()) + ": " + s.reason())
                    .collect(Collectors.joining("; ")));
        }
        BatchDto batch = batchService.createForCases(eligible);
        BatchCloseResult closed = batchService.close(batch.id(), idempotencyKey);
        return new SettleResult(closed, accepted, skipped);
    }

    private static String reasonNotReady(ReconCaseEntity c) {
        if (c.getStatus() == Recon.Status.EXCLUIDO) return "Está excluida";
        if (c.getReceivedAmount() == null || money(c.getReceivedAmount()).signum() == 0) return "Falta registrar el pago";
        if (money(c.getDifference()).signum() != 0) return "No cuadra: diferencia de " + q(c.getDifference());
        return "Estado " + c.getStatus() + ": ábrala para resolverla";
    }
}
