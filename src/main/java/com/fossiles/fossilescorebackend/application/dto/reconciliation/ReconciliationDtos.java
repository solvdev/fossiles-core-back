package com.fossiles.fossilescorebackend.application.dto.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Contratos de la API de conciliación de ventas en línea. */
public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    // ───────── Filtros ─────────

    /** Filtros combinables de la bandeja y del dashboard. Campos vacíos no filtran. */
    public record CaseFilter(
            LocalDate from,
            LocalDate to,
            String method,
            String carrier,
            Long sellerId,
            List<Recon.Status> statuses,
            Long batchId,
            boolean withoutBatch,
            String q,
            Recon.DifferenceType problem,
            Recon.Origin origin) {
    }

    // ───────── Bandeja y detalle ─────────

    public record CaseRow(
            Long id, Long onlineSaleId, LocalDate saleDate, String saleNumber, String shipmentNumber, String guideNumber,
            String carrier, String paymentMethod, String reference, BigDecimal expected, BigDecimal received,
            BigDecimal difference, BigDecimal authorizedAdjustment, BigDecimal pendingBalance, BigDecimal commission,
            String seller, String status, String origin, Long batchId, String batchCode, int criticalCount,
            int warningCount, String problemType, boolean hasReceipts) {
    }

    public record CasePage(List<CaseRow> rows, long total, int page, int size) {
    }

    public record CheckDto(String code, String name, String severity, String outcome, String expected, String found,
                           String message, String action) {
    }

    public record ApplicationDto(Long id, Long receiptId, BigDecimal amountApplied, String matchMethod, String status,
                                 LocalDateTime appliedAt, Long appliedBy, String appliedByName, String source, String carrier, String guide,
                                 String reference, LocalDate receivedDate, BigDecimal receiptAmount, BigDecimal shippingAmount) {
    }

    public record DifferenceDto(Long id, String type, BigDecimal amount, String status, String reason, Long proposedBy,
                                String proposedByName, LocalDateTime proposedAt, Long authorizedBy, String authorizedByName,
                                LocalDateTime authorizedAt) {
    }

    public record EventDto(Long id, String type, String from, String to, String payload, Long actorId, String actorName,
                           LocalDateTime at) {
    }

    public record LedgerDto(Long id, String type, BigDecimal amount, Long batchId, String note, LocalDateTime at) {
    }

    public record SaleItemInfo(String code, String name, String color, String size, Integer quantity, BigDecimal unitPrice,
                               BigDecimal subtotal) {
    }

    public record SaleInfo(String saleNumber, String shipmentNumber, LocalDate saleDate, String customerName, String saleStatus,
                           String paymentMethod, String paymentAuthorization, String carrier, String guideNumber,
                           String salesperson, BigDecimal net, BigDecimal shipping, BigDecimal total, Long invoiceId,
                           LocalDateTime dispatchedAt, LocalDateTime lastUpdatedAt, List<SaleItemInfo> items) {
    }

    public record CommissionDto(String month, BigDecimal base, BigDecimal rate, BigDecimal expected, BigDecimal registered,
                                String status) {
    }

    public record CaseDetail(CaseRow row, SaleInfo sale, List<CheckDto> checks, List<ApplicationDto> applications,
                             List<DifferenceDto> differences, List<EventDto> events, List<LedgerDto> ledger,
                             CommissionDto commission, boolean sourceChanged, List<String> actions) {
    }

    public record InvalidCaseRef(Long caseId, String shipmentNumber, String reason) {
    }

    public record BulkResult(int done, List<InvalidCaseRef> skipped) {
    }

    public record GenerateResult(int created, int alreadyExisted, int excluded, int total) {
    }

    // ───────── Fondos ─────────

    public record ReceiptDto(Long id, String source, String carrier, String guide, String reference, String authorization,
                             LocalDate receivedDate, BigDecimal amount, BigDecimal shippingAmount, BigDecimal applied,
                             BigDecimal remaining, String status, Long importFileId) {
    }

    public record CandidateDto(Long caseId, String shipmentNumber, String saleNumber, LocalDate saleDate, BigDecimal expected,
                               String carrier, String seller, String reason) {
    }

    /** Fondo sin aplicar con lo que se encontró: exacto, ambiguo, bloqueado o sin coincidencia, más candidatos para revisar. */
    public record ReceiptMatch(ReceiptDto receipt, String kind, String reason, List<CandidateDto> candidates) {
    }

    public record ReceiptRequest(Recon.SourceType sourceType, String carrier, String guideNumber, String reference,
                                 String authorizationNumber, LocalDate receivedDate, BigDecimal amount,
                                 BigDecimal shippingAmount) {
    }

    public record ApplyRequest(Long receiptId, BigDecimal amount) {
    }

    /** Pago escrito a mano dentro de un caso. La fuente es opcional: se deduce de la forma de pago de la venta. */
    public record CaseReceiptRequest(Recon.SourceType sourceType, String reference, LocalDate receivedDate, BigDecimal amount,
                                     BigDecimal shippingAmount) {
    }

    /** Captura rápida: se escribe el ENVL o la guía, el monto y la referencia. */
    public record QuickReceiptRequest(String identifier, Recon.SourceType sourceType, String reference, LocalDate receivedDate,
                                      BigDecimal amount, BigDecimal shippingAmount) {
    }

    public record QuickReceiptResult(Long caseId, String shipmentNumber, BigDecimal expected, BigDecimal received, BigDecimal difference,
                                     String status, String message) {
    }

    /** Un pago escrito dentro de la liquidación: monto escrito, o {@code matchExpected} para registrar exactamente lo cobrado. */
    public record BulkReceiptItem(Long caseId, BigDecimal amount, boolean matchExpected, String reference) {
    }

    public record BulkReceiptRequest(List<BulkReceiptItem> items) {
    }

    public record BulkReceiptRow(Long caseId, String shipmentNumber, boolean ok, String message, BigDecimal received,
                                 BigDecimal difference, String status) {
    }

    public record BulkReceiptResult(int saved, int failed, List<BulkReceiptRow> rows) {
    }

    public record RowReport(int row, String status, String message) {
    }

    public record ImportResult(Long fileId, boolean duplicateFile, int rows, int imported, int duplicatedRows,
                               int rejectedRows, int autoApplied, int ambiguous, int blocked, int unmatched,
                               List<RowReport> report) {
    }

    // ───────── Diferencias ─────────

    public record AdjustmentRequest(String reason) {
    }

    public record ReasonRequest(String reason) {
    }

    // ───────── Lotes ─────────

    public record BatchCreateRequest(LocalDate from, LocalDate to, String carrier, String method) {
    }

    public record BatchDto(Long id, String code, String status, LocalDate periodFrom, LocalDate periodTo, int cases,
                           BigDecimal expected, BigDecimal received, BigDecimal adjusted, BigDecimal pending,
                           LocalDateTime createdAt, LocalDateTime closedAt, Long closedBy, String closedByName, String voidReason) {
    }

    public record InvalidCase(Long caseId, String shipmentNumber, String status, String reason) {
    }

    public record BatchCloseResult(BatchDto batch, boolean alreadyClosed, String contentSha256, Long exportId,
                                   int exportedCases, int closedCases) {
    }

    /** Liquidar: lote, cierre y Excel en un solo paso para exactamente los casos elegidos. */
    public record SettleRequest(List<Long> caseIds) {
    }

    public record SettleResult(BatchCloseResult close, int acceptedWithWarnings, List<InvalidCaseRef> skipped) {
    }

    public record ExportDto(Long id, Long batchId, String batchCode, String sha256, int rows, String kind,
                            LocalDateTime createdAt, Long createdBy, String createdByName) {
    }

    // ───────── Dashboard ─────────

    public record Bucket(String key, long cases, long reconciled, long pending, long withDifferences, long incomplete,
                         BigDecimal expected, BigDecimal received) {
    }

    public record Dashboard(Map<String, Long> byStatus, long pending, long reconciled, long withDifferences, long incomplete,
                            long excluded, BigDecimal expected, BigDecimal received, BigDecimal pendingDifferences,
                            BigDecimal awaitingFunds, long commissionsToValidate, List<Bucket> byMethod, List<Bucket> byCarrier,
                            Map<String, Long> byProblem, long unappliedReceipts) {
    }

    // ───────── Comisiones ─────────

    public record SellerCommission(Long sellerId, String seller, int cases, BigDecimal base, BigDecimal rate,
                                   BigDecimal commission, int annulled, int differing) {
    }

    public record CommissionSummary(String month, List<SellerCommission> sellers, int pendingCases, int casesWithoutSeller,
                                    boolean canClose, boolean closed, BigDecimal totalCommission) {
    }

    public record SellerDto(Long id, String name, boolean active) {
    }

    // ───────── Guías históricas ─────────

    public record GuideImportResult(boolean dryRun, int rows, int toSet, int set, int sameAlready, int conflicts,
                                    int notFound, List<RowReport> report) {
    }
}
