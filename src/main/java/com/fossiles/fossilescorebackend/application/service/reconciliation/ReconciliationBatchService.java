package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationExcelExporter.Adjustment;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/**
 * Lotes de descarga. Un lote agrupa casos ya conciliados; al cerrarlo se congelan, se registra la descarga en el
 * libro (un movimiento por caso) y se guarda el contenido exportado. Cerrar dos veces no duplica nada, un caso solo
 * puede estar en un lote vigente y un lote no se cierra si algún caso dejó de estar conciliado o tiene saldo.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationBatchService {

    private final ReconBatchRepository batchRepository;
    private final ReconCaseRepository caseRepository;
    private final ReconExportRepository exportRepository;
    private final ReconExportLineRepository exportLineRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconReceiptRepository receiptRepository;
    private final ReconDifferenceRepository differenceRepository;
    private final OnlineSaleRepository saleRepository;
    private final UserRepository userRepository;
    private final ReconciliationLedgerService ledger;
    private final ReconciliationRecomputeService recompute;
    private final ReconEventService events;
    private final ReconciliationProperties props;
    private final ReconActor actor;

    public record ExportFile(byte[] bytes, String fileName, String sha256) {
    }

    // ───────── crear y consultar ─────────

    @Transactional
    public BatchDto create(BatchCreateRequest req) throws BusinessException {
        if (req == null || req.from() == null || req.to() == null || req.to().isBefore(req.from())) {
            throw new BusinessException("Indique el período del lote (desde y hasta).");
        }
        List<ReconCaseEntity> available = caseRepository.findAvailableForBatch(req.from(), req.to(), Recon.Status.RECONCILED).stream()
                .filter(c -> blank(req.carrier()) || carrierKey(req.carrier()).equals(carrierKey(c.getCarrier())))
                .filter(c -> blank(req.method()) || req.method().equals(c.getPaymentMethod())).toList();
        if (available.isEmpty()) {
            throw new BusinessException("No hay casos conciliados sin lote en ese período. Concilie casos en la bandeja primero.");
        }
        String prefix = "LC-" + req.to().format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        long n = batchRepository.countByCodeStartingWith(prefix) + 1;
        String code = prefix + String.format("%03d", n);
        while (batchRepository.findByCode(code).isPresent()) code = prefix + String.format("%03d", ++n);

        ReconBatchEntity batch = batchRepository.save(ReconBatchEntity.builder().code(code).periodFrom(req.from()).periodTo(req.to())
                .createdBy(actor.id()).build());
        for (ReconCaseEntity c : available) {
            c.setBatchId(batch.getId());
            c.setUpdatedBy(actor.id());
        }
        caseRepository.saveAll(available);
        refreshTotals(batch, available);
        events.record(null, batch.getId(), "LOTE_CREADO", null, null, Map.of("codigo", code, "casos", available.size()));
        return toDto(batch, available);
    }

    @Transactional(readOnly = true)
    public List<BatchDto> list() {
        return batchRepository.findAllByOrderByIdDesc().stream().map(b -> toDto(b, caseRepository.findByBatchId(b.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public BatchDto get(Long id) throws ResourceNotFoundException {
        ReconBatchEntity b = batchRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Lote no encontrado: " + id));
        return toDto(b, caseRepository.findByBatchId(id));
    }

    @Transactional(readOnly = true)
    public List<ExportDto> exports() {
        Map<Long, String> codes = batchRepository.findAll().stream().collect(Collectors.toMap(ReconBatchEntity::getId, ReconBatchEntity::getCode));
        List<ReconExportEntity> all = exportRepository.findAllByOrderByIdDesc();
        Map<Long, String> names = userNames(all.stream().map(ReconExportEntity::getCreatedBy).filter(Objects::nonNull).distinct().toList());
        return all.stream().map(e -> new ExportDto(e.getId(), e.getBatchId(), codes.get(e.getBatchId()),
                e.getFileSha256(), e.getRowCount(), e.getKind(), e.getCreatedAt(), e.getCreatedBy(), names.get(e.getCreatedBy()))).toList();
    }

    /** Saca un caso de un lote abierto (por ejemplo porque se reabrió). */
    @Transactional
    public BatchDto release(Long batchId, Long caseId) throws ResourceNotFoundException, BusinessException {
        ReconBatchEntity b = batchRepository.findByIdForUpdate(batchId).orElseThrow(() -> new ResourceNotFoundException("Lote no encontrado: " + batchId));
        if (b.getStatus() != Recon.BatchStatus.OPEN) throw new BusinessException("Solo se pueden sacar casos de un lote abierto.");
        ReconCaseEntity c = caseRepository.findById(caseId).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + caseId));
        if (!batchId.equals(c.getBatchId())) throw new BusinessException("El caso no pertenece a este lote.");
        c.setBatchId(null);
        caseRepository.save(c);
        List<ReconCaseEntity> rest = caseRepository.findByBatchId(batchId);
        refreshTotals(b, rest);
        events.record(caseId, batchId, "CASO_SACADO_DEL_LOTE", c.getStatus(), c.getStatus(), Map.of("lote", b.getCode()));
        return toDto(b, rest);
    }

    // ───────── cierre ─────────

    @Transactional
    public BatchCloseResult close(Long batchId, String idempotencyKey) throws ResourceNotFoundException, BusinessException {
        ReconBatchEntity batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Lote no encontrado: " + batchId));
        if (batch.getStatus() == Recon.BatchStatus.CLOSED) {
            ReconExportEntity last = exportRepository.findByBatchIdOrderByIdDesc(batchId).stream()
                    .filter(e -> "CIERRE".equals(e.getKind())).findFirst().orElse(null);
            List<ReconCaseEntity> cases = caseRepository.findByBatchId(batchId);
            return new BatchCloseResult(toDto(batch, cases), true, last == null ? null : last.getFileSha256(), last == null ? null : last.getId(),
                    last == null ? 0 : last.getRowCount(), cases.size());
        }
        if (batch.getStatus() == Recon.BatchStatus.VOID) throw new BusinessException("El lote está anulado.");
        if (!blank(idempotencyKey)) {
            Optional<ReconBatchEntity> other = batchRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (other.isPresent() && !other.get().getId().equals(batchId)) {
                throw new BusinessException("Esa clave de cierre ya se usó en otro lote.");
            }
        }
        List<ReconCaseEntity> cases = caseRepository.findByBatchIdForUpdate(batchId);
        if (cases.isEmpty()) throw new BusinessException("El lote no tiene casos.");

        recompute.recomputeAll(cases);   // el estado de cada caso debe estar al día al momento de cerrar
        List<String> invalid = new ArrayList<>();
        for (ReconCaseEntity c : cases) {
            if (!c.getStatus().isReconciled()) {
                invalid.add(label(c) + ": está «" + c.getStatus() + "»");
            } else if (money(c.getPendingBalance()).signum() != 0) {
                invalid.add(label(c) + ": saldo pendiente " + q(c.getPendingBalance()));
            }
        }
        if (!invalid.isEmpty()) {
            throw new BusinessException("No se puede cerrar el lote: " + invalid.size() + " caso(s) no están listos. "
                    + String.join("; ", invalid.stream().limit(10).toList()) + (invalid.size() > 10 ? "; y " + (invalid.size() - 10) + " más." : "."));
        }

        recompute.persistChecks(cases);   // foto de las reglas con las que se cerró cada caso
        LocalDateTime now = LocalDateTime.now();
        Long userId = actor.id();
        for (ReconCaseEntity c : cases) {
            ledger.postDischarge(c, batch.getId(), batch.getCode());
            c.setClosedAt(now);
            c.setUpdatedBy(userId);
        }
        caseRepository.saveAll(cases);
        refreshTotals(batch, cases);

        List<ReconciliationExcelExporter.Row> rows = rowsFor(cases);
        String sha = ReconciliationExcelExporter.contentSha(rows);
        ReconExportEntity export = exportRepository.save(ReconExportEntity.builder().batchId(batchId).fileSha256(sha).rowCount(rows.size())
                .kind("CIERRE").createdBy(userId).build());
        exportLineRepository.saveAll(cases.stream().map(c -> ReconExportLineEntity.builder().exportId(export.getId()).caseId(c.getId()).build()).toList());

        batch.setStatus(Recon.BatchStatus.CLOSED);
        batch.setClosedAt(now);
        batch.setClosedBy(userId);
        batch.setIdempotencyKey(blank(idempotencyKey) ? null : idempotencyKey.trim());
        batchRepository.save(batch);
        events.record(null, batchId, "LOTE_CERRADO", null, null,
                Map.of("codigo", batch.getCode(), "casos", cases.size(), "huella", sha, "cobrado", batch.getTotalExpected().toPlainString(),
                        "recibido", batch.getTotalReceived().toPlainString(), "ajustes", batch.getTotalAdjusted().toPlainString()));
        for (ReconCaseEntity c : cases) {
            events.record(c.getId(), batchId, "DESCARGADO", c.getStatus(), c.getStatus(), Map.of("lote", batch.getCode()));
        }
        return new BatchCloseResult(toDto(batch, cases), false, sha, export.getId(), rows.size(), cases.size());
    }

    // ───────── anulación ─────────

    /** Anula un lote. Si estaba cerrado, la descarga se revierte con movimientos nuevos (nada se borra). */
    @Transactional
    public BatchDto voidBatch(Long batchId, String reason) throws ResourceNotFoundException, BusinessException {
        if (reason == null || reason.trim().length() < 10) throw new BusinessException("Escriba el motivo de la anulación (mínimo 10 caracteres).");
        ReconBatchEntity batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Lote no encontrado: " + batchId));
        if (batch.getStatus() == Recon.BatchStatus.VOID) throw new BusinessException("El lote ya está anulado.");
        boolean wasClosed = batch.getStatus() == Recon.BatchStatus.CLOSED;
        List<ReconCaseEntity> cases = caseRepository.findByBatchIdForUpdate(batchId);
        if (wasClosed) ledger.reverseBatch(batchId, reason.trim());
        for (ReconCaseEntity c : cases) {
            c.setBatchId(null);
            c.setClosedAt(null);
        }
        caseRepository.saveAll(cases);
        batch.setStatus(Recon.BatchStatus.VOID);
        batch.setVoidReason(reason.trim());
        batch.setVoidedAt(LocalDateTime.now());
        batch.setVoidedBy(actor.id());
        batchRepository.save(batch);
        recompute.recomputeAll(cases);
        events.record(null, batchId, "LOTE_ANULADO", null, null,
                Map.of("codigo", batch.getCode(), "motivo", reason.trim(), "estabaCerrado", wasClosed, "casos", cases.size()));
        for (ReconCaseEntity c : cases) {
            events.record(c.getId(), batchId, "LOTE_ANULADO", c.getStatus(), c.getStatus(), Map.of("lote", batch.getCode(), "motivo", reason.trim()));
        }
        return toDto(batch, cases);
    }

    // ───────── descarga del archivo ─────────

    /** Genera el Excel del lote con las mismas filas congeladas. Cada descarga queda registrada. */
    @Transactional
    public ExportFile download(Long batchId) throws ResourceNotFoundException, BusinessException {
        ReconBatchEntity batch = batchRepository.findById(batchId).orElseThrow(() -> new ResourceNotFoundException("Lote no encontrado: " + batchId));
        if (batch.getStatus() == Recon.BatchStatus.VOID) throw new BusinessException("El lote está anulado y no tiene descarga.");
        List<ReconCaseEntity> cases = caseRepository.findByBatchId(batchId);
        List<ReconciliationExcelExporter.Row> rows = rowsFor(cases);
        String sha = ReconciliationExcelExporter.contentSha(rows);
        if (batch.getStatus() == Recon.BatchStatus.CLOSED) {
            exportRepository.findByBatchIdOrderByIdDesc(batchId).stream().filter(e -> "CIERRE".equals(e.getKind())).findFirst().ifPresent(closing -> {
                if (!closing.getFileSha256().equals(sha)) {
                    throw new IllegalStateException("El contenido del lote " + batch.getCode() + " cambió desde el cierre.");
                }
            });
            exportRepository.save(ReconExportEntity.builder().batchId(batchId).fileSha256(sha).rowCount(rows.size()).kind("DESCARGA")
                    .createdBy(actor.id()).build());
        }
        Map<Long, String> names = userNames(Stream.of(batch.getClosedBy()).filter(Objects::nonNull).toList());
        ReconciliationExcelExporter.Data data = new ReconciliationExcelExporter.Data(batch.getCode(), batch.getPeriodFrom(), batch.getPeriodTo(),
                batch.getStatus().name(), batch.getClosedAt(), names.get(batch.getClosedBy()), sha, rows.size(), cases.size(), rows, adjustmentsFor(cases));
        return new ExportFile(ReconciliationExcelExporter.build(data), "conciliacion-" + batch.getCode() + ".xlsx", sha);
    }

    // ───────── apoyo ─────────

    private List<ReconciliationExcelExporter.Row> rowsFor(List<ReconCaseEntity> cases) {
        List<Long> caseIds = cases.stream().map(ReconCaseEntity::getId).toList();
        Map<Long, OnlineSaleEntity> sales = saleRepository.findAllById(cases.stream().map(ReconCaseEntity::getOnlineSaleId).toList()).stream()
                .collect(Collectors.toMap(OnlineSaleEntity::getId, s -> s));
        Map<Long, List<ReconReceiptApplicationEntity>> apps = applicationRepository.findByCaseIdInAndStatus(caseIds, Recon.RowStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(ReconReceiptApplicationEntity::getCaseId));
        Map<Long, ReconReceiptEntity> receipts = receiptRepository.findByIdIn(apps.values().stream().flatMap(List::stream)
                .map(ReconReceiptApplicationEntity::getReceiptId).distinct().toList()).stream().collect(Collectors.toMap(ReconReceiptEntity::getId, r -> r));
        List<ReconciliationExcelExporter.Row> rows = new ArrayList<>();
        for (ReconCaseEntity c : cases) {
            OnlineSaleEntity sale = sales.get(c.getOnlineSaleId());
            String reference = apps.getOrDefault(c.getId(), List.of()).stream().map(a -> receipts.get(a.getReceiptId())).filter(Objects::nonNull)
                    .map(r -> !blank(r.getReference()) ? r.getReference() : r.getAuthorizationNumber()).filter(s -> !blank(s)).distinct()
                    .collect(Collectors.joining(", "));
            BigDecimal net = sale == null ? BigDecimal.ZERO : money(sale.getNetAmount());
            BigDecimal commission = net.multiply(props.getCommissionRate()).setScale(2, RoundingMode.HALF_UP);
            rows.add(new ReconciliationExcelExporter.Row(c.getId(), c.getSaleDate(), c.getSaleNumber(), c.getShipmentNumber(), c.getGuideNumber(),
                    c.getCarrier(), c.getPaymentMethod(), reference, money(c.getExpectedAmount()), money(c.getReceivedAmount()),
                    money(c.getDifference()), money(c.getAuthorizedAdjustment()), c.getSalesperson(), commission, c.getStatus().name(), c.getOrigin().name()));
        }
        return rows;
    }

    private List<Adjustment> adjustmentsFor(List<ReconCaseEntity> cases) {
        Map<Long, ReconCaseEntity> byId = cases.stream().collect(Collectors.toMap(ReconCaseEntity::getId, c -> c));
        List<ReconDifferenceEntity> diffs = differenceRepository.findByCaseIdInAndStatus(new ArrayList<>(byId.keySet()), Recon.DifferenceStatus.AUTHORIZED);
        List<Long> userIds = diffs.stream().flatMap(d -> Stream.of(d.getProposedBy(), d.getAuthorizedBy())).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = userNames(userIds);
        return diffs.stream().map(d -> new Adjustment(byId.get(d.getCaseId()).getShipmentNumber(), d.getAmount(), d.getReason(),
                names.get(d.getProposedBy()), names.get(d.getAuthorizedBy()), d.getAuthorizedAt())).toList();
    }

    private Map<Long, String> userNames(List<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (UserEntity u : userRepository.findAllById(ids)) out.put(u.getId(), u.getUsername());
        return out;
    }

    private void refreshTotals(ReconBatchEntity b, List<ReconCaseEntity> cases) {
        b.setTotalExpected(money(cases.stream().map(ReconCaseEntity::getExpectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        b.setTotalReceived(money(cases.stream().map(ReconCaseEntity::getReceivedAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        b.setTotalAdjusted(money(cases.stream().map(ReconCaseEntity::getAuthorizedAdjustment).reduce(BigDecimal.ZERO, BigDecimal::add)));
        batchRepository.save(b);
    }

    private BatchDto toDto(ReconBatchEntity b, List<ReconCaseEntity> cases) {
        String closedByName = b.getClosedBy() == null ? null : userNames(List.of(b.getClosedBy())).get(b.getClosedBy());
        BigDecimal pending = b.getTotalExpected().subtract(b.getTotalReceived()).subtract(b.getTotalAdjusted());
        return new BatchDto(b.getId(), b.getCode(), b.getStatus().name(), b.getPeriodFrom(), b.getPeriodTo(), cases.size(), b.getTotalExpected(),
                b.getTotalReceived(), b.getTotalAdjusted(), money(pending), b.getCreatedAt(), b.getClosedAt(), b.getClosedBy(), closedByName, b.getVoidReason());
    }

    private static String label(ReconCaseEntity c) {
        return c.getShipmentNumber() == null ? "caso " + c.getId() : c.getShipmentNumber();
    }
}
