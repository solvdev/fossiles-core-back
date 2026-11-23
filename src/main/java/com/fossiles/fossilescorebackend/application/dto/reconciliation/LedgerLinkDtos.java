package com.fossiles.fossilescorebackend.application.dto.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Liquidar un periodo de revisión del libro de ventas en línea desde la Conciliación: se compara lo que Contabilidad dejó
 * revisado en el libro contra lo que tiene la Conciliación, se muestran las diferencias y, al aprobar, la Conciliación queda
 * como el libro, se liquida (lote + descarga + Excel) y se cierra la revisión.
 */
public final class LedgerLinkDtos {

    private LedgerLinkDtos() {
    }

    /** Lote de liquidación asociado a un periodo. */
    public record LinkedBatch(Long id, String code, String status, int cases, BigDecimal expected, BigDecimal received, LocalDateTime closedAt) {
    }

    /** Un periodo de revisión del libro, con su avance y los lotes que ya se liquidaron con él. */
    public record PeriodOption(Long id, LocalDate from, LocalDate to, String label, String status, String closedByName, LocalDateTime closedAt,
                               long sales, long reviewed, List<LinkedBatch> batches) {
    }

    /** Una diferencia entre el libro y la Conciliación. {@code fixable}: el botón la sobrescribe; si no, es solo informativa o bloquea. */
    public record FieldDiff(String key, String label, String ledger, String recon, boolean fixable, String note) {
    }

    /**
     * Una venta del periodo. {@code state}: LISTA (cuadra tal cual), LISTA_CON_CAMBIOS (cuadra después de sobrescribir),
     * EXCLUIR (el libro la tiene devuelta/anulada), NO_LISTA (aun con el libro no cuadra), SIN_REVISAR, YA_LIQUIDADA, NO_ENTRA, NO_DESPACHADA.
     */
    public record Row(Long saleId, String saleNumber, String shipmentNumber, String customer, LocalDate saleDate, String carrier, String paymentMethod,
                      boolean reviewed, Long caseId, String caseStatus, String batchCode, String state, String reason, boolean selectable,
                      BigDecimal ledgerTotal, BigDecimal reconExpected, BigDecimal ledgerDeposit, BigDecimal reconReceived,
                      BigDecimal afterDifference, List<FieldDiff> diffs) {
    }

    public record Summary(int sales, int reviewed, int unreviewed, int ready, int readyWithChanges, int toExclude, int notReady, int alreadySettled,
                          int notApplicable, int differences, BigDecimal readyAmount) {
    }

    public record Preview(PeriodOption period, Summary summary, List<Row> rows, boolean canApply, boolean canClosePeriod, String closeBlocker,
                          boolean assumeEqual, List<String> warnings) {
    }

    /** {@code assumeEqual}: si el libro no anota depósito real y no hay pago, tomar lo recibido = lo cobrado (como el botón «=»). */
    public record ApplyRequest(List<Long> saleIds, boolean closePeriod, boolean assumeEqual) {
    }

    public record Blocked(Long saleId, String saleNumber, String shipmentNumber, String reason) {
    }

    /** {@code applied=false}: no se cambió nada (todo o nada); {@code blocked} dice cuáles ventas lo impidieron. */
    public record ApplyResult(boolean applied, Long batchId, String batchCode, int liquidated, int excluded, int overwritten, BigDecimal liquidatedAmount,
                              boolean periodClosed, String message, List<Blocked> blocked) {
    }
}
