package com.fossiles.fossilescorebackend.application.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Cuentas por pagar, pagos a proveedores y su configuración (bancos, formas de pago, formato de cheque). */
public final class SupplierPaymentDtos {

    private SupplierPaymentDtos() {
    }

    // ------------------------------------------------------------------ Configuración

    /**
     * Un dato del cheque. {@code key}: DATE, AMOUNT, PAYEE, WORDS, CONCEPT, NUMBER o LEGEND. {@code top} se mide desde
     * arriba y {@code edge} desde el lado {@code side} (LEFT o RIGHT), todo en milímetros; {@code size} en puntos.
     */
    public record LayoutField(String key, boolean on, double top, String side, double edge, double size) {
    }

    /** {@code fontFamily}: TIMES, ARIAL o COURIER. */
    public record ChequeLayoutDto(Long id, String name, double widthMm, double heightMm, String fontFamily,
                                  List<LayoutField> fields, boolean active) {
    }

    public record BankAccountDto(Long id, String name, String accountNumber, String ledgerCode, String ledgerName,
                                 String firstChequeNumber, boolean sharesPayrollChecks, Long chequeLayoutId,
                                 String chequeLayoutName, boolean active, String nextChequeNumber) {
    }

    public record PaymentMethodDto(Long id, String name, boolean requiresBank, boolean requiresReference,
                                   boolean printsCheque, int sortOrder, boolean active) {
    }

    /** {@code payablesStartDate}: las órdenes recibidas antes de esta fecha no aparecen en cuentas por pagar. */
    public record ConfigResponse(List<BankAccountDto> accounts, List<ChequeLayoutDto> layouts,
                                 List<PaymentMethodDto> methods, LocalDate payablesStartDate, boolean canConfigure) {
    }

    // ------------------------------------------------------------------ Cuentas por pagar

    /**
     * Una orden de compra o un documento por pagar. {@code kind}: ORDEN, DOCUMENTO, SALDO_INICIAL o RENTA;
     * {@code rowKey}: "O:<id>" o "D:<id>"; {@code payeeKey} agrupa lo que se puede pagar junto (mismo beneficiario).
     * {@code status}: VENCIDA, POR_VENCER (15 días o menos), PARCIAL (ya tiene un pago) o AL_DIA.
     */
    public record PayableRow(String rowKey, String kind, Long orderId, Long documentId, String code, String payeeKey,
                             Long supplierId, String supplierName, LocalDate orderDate, LocalDate receivedDate,
                             LocalDate dueDate, BigDecimal total, BigDecimal paid, BigDecimal balance, String status,
                             Integer daysToDue, String payeeName, String concept) {
    }

    public record PayablesSummary(BigDecimal totalBalance, int orderCount, int supplierCount, BigDecimal overdueAmount,
                                  int overdueCount, int dueSoonCount, BigDecimal dueIn7DaysAmount, int dueIn7DaysCount,
                                  BigDecimal paidThisMonth, int paymentsThisMonth) {
    }

    public record PayablesResponse(LocalDate today, PayablesSummary summary, List<PayableRow> rows, boolean canPay) {
    }

    // ------------------------------------------------------------------ Pagos

    public record PaymentRow(Long id, String number, Long supplierId, String supplierName, Long paymentMethodId,
                             String methodName, boolean printsCheque, Long bankAccountId, String bankName,
                             LocalDate payDate, BigDecimal amount, String payeeName, String reference, String concept,
                             String status, LocalDateTime printedAt, int printCount, String voidReason,
                             List<String> orderCodes, LocalDateTime createdAt) {
    }

    public record PaymentsResponse(List<PaymentRow> rows, boolean canPay) {
    }

    /** Todo lo que el front necesita para dibujar un cheque. */
    public record ChequePrintItem(Long paymentId, String paymentNumber, String payeeName, BigDecimal amount,
                                  LocalDate payDate, String chequeNumber, String concept, boolean reprint,
                                  ChequeLayoutDto layout) {
    }

    // ------------------------------------------------------------------ Documentos por pagar

    public record DocImportChange(String payeeName, boolean matchedSupplier, String concept, String documentNumber,
                                  LocalDate issueDate, LocalDate dueDate, BigDecimal amount) {
    }

    public record DocImportError(int row, String payeeName, String message) {
    }

    public record DocImportResult(boolean applied, int readRows, BigDecimal total, List<DocImportChange> changes,
                                  List<DocImportError> errors) {
    }

    /** Resultado de generar las rentas de un mes ({@code period} "YYYY-MM"). */
    public record RentGenerationResult(String period, int created, int alreadyExisting, BigDecimal createdTotal,
                                       List<String> skipped) {
    }
}
