package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconLedgerEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconLedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.money;

/**
 * Libro de descargas. Cada caso es su propia cuenta: el cargo nace con la venta, la descarga se registra al
 * cerrar el lote y todo se corrige con reversas, nunca editando ni borrando. Cada movimiento lleva una llave
 * de idempotencia para que repetir una operación no duplique nada.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationLedgerService {

    private final ReconLedgerEntryRepository ledgerRepository;
    private final ReconActor actor;

    /** Cargo = lo cobrado. Con monto cero no hay nada que descargar y no se registra. */
    public Optional<ReconLedgerEntryEntity> postCharge(ReconCaseEntity c) {
        if (money(c.getExpectedAmount()).signum() == 0) return Optional.empty();
        String key = "CARGO-" + c.getId() + "-" + (countOf(c.getId(), Recon.LedgerType.CARGO) + 1);
        return Optional.of(post(c, null, Recon.LedgerType.CARGO, c.getExpectedAmount(), key, null, null));
    }

    /** Primer cargo de un caso recién creado: no hay movimientos previos, así que se omite la consulta. */
    public Optional<ReconLedgerEntryEntity> postInitialCharge(ReconCaseEntity c) {
        if (money(c.getExpectedAmount()).signum() == 0) return Optional.empty();
        return Optional.of(post(c, null, Recon.LedgerType.CARGO, c.getExpectedAmount(), "CARGO-" + c.getId() + "-1", null, null));
    }

    /** Descarga y, si hubo, el ajuste autorizado. Dejan el saldo del caso en cero. Idempotente por caso y lote. */
    public List<ReconLedgerEntryEntity> postDischarge(ReconCaseEntity c, Long batchId, String batchCode) {
        List<ReconLedgerEntryEntity> posted = new ArrayList<>();
        if (money(c.getReceivedAmount()).signum() > 0) {
            posted.add(postOnce(c, batchId, Recon.LedgerType.DESCARGA, c.getReceivedAmount(),
                    "DESCARGA-" + c.getId() + "-" + batchId, "Lote " + batchCode));
        }
        if (money(c.getAuthorizedAdjustment()).signum() > 0) {
            posted.add(postOnce(c, batchId, Recon.LedgerType.AJUSTE_AUTORIZADO, c.getAuthorizedAdjustment(),
                    "AJUSTE-" + c.getId() + "-" + batchId, "Ajuste autorizado, lote " + batchCode));
        }
        return posted;
    }

    /** Reversa todos los movimientos de descarga de un lote anulado. Devuelve las reversas creadas. */
    public List<ReconLedgerEntryEntity> reverseBatch(Long batchId, String reason) {
        List<ReconLedgerEntryEntity> originals = ledgerRepository.findByBatchIdAndEntryTypeIn(
                batchId, List.of(Recon.LedgerType.DESCARGA, Recon.LedgerType.AJUSTE_AUTORIZADO));
        List<ReconLedgerEntryEntity> reversals = new ArrayList<>();
        for (ReconLedgerEntryEntity original : originals) {
            String key = "REV-" + original.getId();
            Optional<ReconLedgerEntryEntity> existing = ledgerRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                reversals.add(existing.get());
                continue;
            }
            reversals.add(ledgerRepository.save(ReconLedgerEntryEntity.builder()
                    .caseId(original.getCaseId())
                    .batchId(batchId)
                    .customerCode(original.getCustomerCode())
                    .entryType(Recon.LedgerType.REVERSA)
                    .amount(original.getAmount())
                    .reversesEntryId(original.getId())
                    .idempotencyKey(key)
                    .note("Reversa de " + original.getEntryType() + ": " + reason)
                    .createdBy(actor.id())
                    .build()));
        }
        return reversals;
    }

    /** Reversa un cargo vigente (cuando el monto de origen cambia y se actualiza el caso). */
    public void reverseCharges(ReconCaseEntity c, String reason) {
        for (ReconLedgerEntryEntity e : ledgerRepository.findByCaseIdOrderByIdAsc(c.getId())) {
            if (e.getEntryType() != Recon.LedgerType.CARGO) continue;
            String key = "REV-" + e.getId();
            if (ledgerRepository.findByIdempotencyKey(key).isPresent()) continue;
            ledgerRepository.save(ReconLedgerEntryEntity.builder()
                    .caseId(c.getId())
                    .entryType(Recon.LedgerType.REVERSA)
                    .amount(e.getAmount())
                    .reversesEntryId(e.getId())
                    .idempotencyKey(key)
                    .note("Reversa de CARGO: " + reason)
                    .createdBy(actor.id())
                    .build());
        }
    }

    /** Saldo del caso en el libro: cargos − descargas − ajustes, con las reversas devolviendo lo que revierten. */
    public BigDecimal balance(Long caseId) {
        List<ReconLedgerEntryEntity> entries = ledgerRepository.findByCaseIdOrderByIdAsc(caseId);
        Map<Long, ReconLedgerEntryEntity> byId = new HashMap<>();
        entries.forEach(e -> byId.put(e.getId(), e));
        BigDecimal balance = BigDecimal.ZERO;
        for (ReconLedgerEntryEntity e : entries) {
            balance = balance.add(signedAmount(e, byId));
        }
        return money(balance);
    }

    static BigDecimal signedAmount(ReconLedgerEntryEntity e, Map<Long, ReconLedgerEntryEntity> byId) {
        BigDecimal amount = money(e.getAmount());
        return switch (e.getEntryType()) {
            case CARGO -> amount;
            case DESCARGA, AJUSTE_AUTORIZADO -> amount.negate();
            case REVERSA -> {
                ReconLedgerEntryEntity original = byId.get(e.getReversesEntryId());
                // revertir un cargo resta deuda; revertir una descarga o ajuste la devuelve
                yield original != null && original.getEntryType() == Recon.LedgerType.CARGO ? amount.negate() : amount;
            }
        };
    }

    private ReconLedgerEntryEntity postOnce(ReconCaseEntity c, Long batchId, Recon.LedgerType type, BigDecimal amount,
                                            String key, String note) {
        Optional<ReconLedgerEntryEntity> existing = ledgerRepository.findByIdempotencyKey(key);
        return existing.orElseGet(() -> post(c, batchId, type, amount, key, note, null));
    }

    private ReconLedgerEntryEntity post(ReconCaseEntity c, Long batchId, Recon.LedgerType type, BigDecimal amount,
                                        String key, String note, Long reverses) {
        return ledgerRepository.save(ReconLedgerEntryEntity.builder()
                .caseId(c.getId())
                .batchId(batchId)
                .entryType(type)
                .amount(money(amount))
                .reversesEntryId(reverses)
                .idempotencyKey(key)
                .note(note)
                .createdBy(actor.id())
                .build());
    }

    private long countOf(Long caseId, Recon.LedgerType type) {
        return ledgerRepository.findByCaseIdOrderByIdAsc(caseId).stream().filter(e -> e.getEntryType() == type).count();
    }
}
