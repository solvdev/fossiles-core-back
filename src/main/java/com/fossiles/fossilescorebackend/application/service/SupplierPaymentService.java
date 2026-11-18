package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ChequePrintItem;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayableRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesSummary;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BankAccountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayableDocumentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentAllocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BankAccountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialReceiptRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayableDocumentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PaymentMethodRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierPaymentAllocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierPaymentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cuentas por pagar y pagos. Se debe cada orden de compra recibida y cada documento por pagar (saldo inicial, servicio,
 * renta…), menos los pagos que no se anularon. Un pago junta varias de esas partidas de un mismo beneficiario y puede
 * cubrir solo una parte de cada una. Cada pago genera su partida contable (cuentas por pagar contra el banco) y, al
 * anularse, la invierte.
 */
@Service
@RequiredArgsConstructor
public class SupplierPaymentService {

    /** Órdenes que ya se recibieron, total o parcialmente: son las que se le deben al proveedor. */
    static final List<String> PAYABLE_STATUSES = List.of("RECIBIDA", "PARCIALMENTE_RECIBIDA");
    static final int DEFAULT_CREDIT_DAYS = 30;
    /** Una partida que vence en estos días o menos aparece «por vencer». */
    static final int DUE_SOON_DAYS = 15;
    /** Órdenes, documentos y saldos iniciales. Sin las rentas. */
    static final String SCOPE_SUPPLIERS = "SUPPLIERS";
    /** Solo documentos de renta. */
    static final String SCOPE_RENTS = "RENTS";

    private final PurchaseOrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final MaterialReceiptRepository receiptRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final PayableDocumentRepository documentRepository;
    private final PaymentMethodRepository methodRepository;
    private final BankAccountRepository accountRepository;
    private final SupplierPaymentConfigService configService;
    private final AccountingService accountingService;
    private final SupplierPaymentAccessGuard guard;

    /** Cada aplicación del pago es a una orden de compra O a un documento por pagar. */
    public record AllocationInput(Long purchaseOrderId, Long payableDocumentId, BigDecimal amount) {}

    public record CreateRequest(Long paymentMethodId, Long bankAccountId, LocalDate payDate, String payeeName,
                                String reference, String concept, List<AllocationInput> allocations) {}

    /** Identifica al beneficiario: «S:id» si es proveedor, «N:nombre» (sin acentos ni mayúsculas) si no. */
    static String payeeKeyOf(Long supplierId, String payeeName) {
        return supplierId != null ? "S:" + supplierId : "N:" + normalizeName(payeeName);
    }

    static String normalizeName(String value) {
        String noAccents = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------------
    // Cuentas por pagar
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PayablesResponse payables() throws BusinessException {
        return payables(null);
    }

    /**
     * {@code scope} nulo: todo, como siempre. {@code SUPPLIERS}: órdenes, documentos y saldos. {@code RENTS}: rentas.
     * Los totales se recalculan sobre las filas que quedan. {@code orderCount} cuenta solo órdenes y
     * {@code supplierCount} solo beneficiarios que no son renta, con o sin scope.
     */
    @Transactional(readOnly = true)
    public PayablesResponse payables(String scope) throws BusinessException {
        guard.assertCanView();
        String normalized = normalizeScope(scope);
        LocalDate today = GuatemalaDateTime.today();
        List<PayableRow> rows = buildPayables(today);
        if (normalized != null) {
            rows = rows.stream().filter(row -> rowInScope(row.kind(), normalized)).toList();
        }

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal in7 = BigDecimal.ZERO;
        int overdueCount = 0;
        int dueSoon = 0;
        int in7Count = 0;
        int orderCount = 0;
        Set<String> supplierPayees = new HashSet<>();
        for (PayableRow r : rows) {
            total = total.add(r.balance());
            if ("VENCIDA".equals(r.status())) {
                overdue = overdue.add(r.balance());
                overdueCount++;
            } else if ("POR_VENCER".equals(r.status())) {
                dueSoon++;
            }
            if (r.daysToDue() != null && r.daysToDue() >= 0 && r.daysToDue() <= 7) {
                in7 = in7.add(r.balance());
                in7Count++;
            }
            if ("ORDEN".equals(r.kind())) {
                orderCount++;
            }
            if (!"RENTA".equals(r.kind())) {
                supplierPayees.add(r.payeeKey());
            }
        }
        MonthPaid paid = paidInMonth(today.withDayOfMonth(1), normalized);
        PayablesSummary summary = new PayablesSummary(total, orderCount, supplierPayees.size(), overdue, overdueCount,
                dueSoon, in7, in7Count, paid.amount(), paid.count());
        return new PayablesResponse(today, summary, rows, guard.canPay());
    }

    private static String normalizeScope(String scope) throws BusinessException {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String value = scope.trim();
        if (SCOPE_SUPPLIERS.equals(value) || SCOPE_RENTS.equals(value)) {
            return value;
        }
        throw new BusinessException("El alcance debe ser SUPPLIERS o RENTS.");
    }

    private static boolean rowInScope(String kind, String scope) {
        boolean rent = "RENTA".equals(kind);
        return SCOPE_RENTS.equals(scope) ? rent : !rent;
    }

    private record MonthPaid(BigDecimal amount, int count) {}

    /** Sin scope suma todos los pagos del mes. Con scope, solo los pagos cuyas partidas son todas de ese alcance. */
    private MonthPaid paidInMonth(LocalDate monthStart, String scope) {
        List<SupplierPaymentEntity> payments = new ArrayList<>();
        for (SupplierPaymentEntity payment : paymentRepository.findAll()) {
            if (!SupplierPaymentEntity.STATUS_VOID.equals(payment.getStatus()) && !payment.getPayDate().isBefore(monthStart)) {
                payments.add(payment);
            }
        }
        if (scope == null) {
            BigDecimal total = BigDecimal.ZERO;
            for (SupplierPaymentEntity payment : payments) {
                total = total.add(payment.getAmount());
            }
            return new MonthPaid(total, payments.size());
        }
        if (payments.isEmpty()) {
            return new MonthPaid(BigDecimal.ZERO, 0);
        }
        List<SupplierPaymentAllocationEntity> allocations = allocationRepository.findByPaymentIdIn(
                payments.stream().map(SupplierPaymentEntity::getId).toList());
        Map<Long, List<SupplierPaymentAllocationEntity>> byPayment = new HashMap<>();
        Set<Long> documentIds = new HashSet<>();
        for (SupplierPaymentAllocationEntity allocation : allocations) {
            byPayment.computeIfAbsent(allocation.getPaymentId(), id -> new ArrayList<>()).add(allocation);
            if (allocation.getPayableDocumentId() != null) {
                documentIds.add(allocation.getPayableDocumentId());
            }
        }
        Map<Long, String> kindByDocument = new HashMap<>();
        if (!documentIds.isEmpty()) {
            documentRepository.findAllById(documentIds).forEach(document -> kindByDocument.put(document.getId(), document.getKind()));
        }
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (SupplierPaymentEntity payment : payments) {
            List<SupplierPaymentAllocationEntity> rows = byPayment.get(payment.getId());
            if (rows == null || rows.isEmpty() || rows.stream().anyMatch(row -> !allocationInScope(row, kindByDocument, scope))) {
                continue;
            }
            total = total.add(payment.getAmount());
            count++;
        }
        return new MonthPaid(total, count);
    }

    private static boolean allocationInScope(SupplierPaymentAllocationEntity allocation, Map<Long, String> kindByDocument,
                                             String scope) {
        boolean rent = allocation.getPayableDocumentId() != null
                && PayableDocumentEntity.KIND_RENT.equals(kindByDocument.get(allocation.getPayableDocumentId()));
        return SCOPE_RENTS.equals(scope) ? rent : !rent;
    }

    List<PayableRow> buildPayables(LocalDate today) {
        LocalDate start = configService.payablesStartDate();
        List<PayableRow> rows = new ArrayList<>(orderRows(today, start));
        rows.addAll(documentRows(today));
        rows.sort(Comparator.comparing(PayableRow::supplierName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PayableRow::dueDate));
        return rows;
    }

    private static String statusOf(int daysToDue, BigDecimal alreadyPaid) {
        return daysToDue < 0 ? "VENCIDA"
                : daysToDue <= DUE_SOON_DAYS ? "POR_VENCER"
                : alreadyPaid.signum() > 0 ? "PARCIAL" : "AL_DIA";
    }

    private List<PayableRow> orderRows(LocalDate today, LocalDate start) {
        List<PurchaseOrderEntity> orders = new ArrayList<>();
        for (String status : PAYABLE_STATUSES) {
            orders.addAll(orderRepository.findByStatus(status));
        }
        orders.removeIf(o -> o.getTotal() == null || o.getTotal().signum() <= 0 || o.getSupplierId() == null);
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> paid = new HashMap<>();
        allocationRepository.paidByOrder().forEach(r -> paid.put(((Number) r[0]).longValue(), (BigDecimal) r[1]));
        Map<Long, LocalDate> received = new HashMap<>();
        receiptRepository.findLastReceiptDates(orders.stream().map(PurchaseOrderEntity::getId).toList())
                .forEach(r -> received.put(((Number) r[0]).longValue(), (LocalDate) r[1]));
        Map<Long, SupplierEntity> suppliers = new HashMap<>();
        supplierRepository.findAllById(orders.stream().map(PurchaseOrderEntity::getSupplierId).collect(Collectors.toSet()))
                .forEach(s -> suppliers.put(s.getId(), s));

        List<PayableRow> rows = new ArrayList<>();
        for (PurchaseOrderEntity o : orders) {
            BigDecimal alreadyPaid = paid.getOrDefault(o.getId(), BigDecimal.ZERO);
            BigDecimal balance = o.getTotal().subtract(alreadyPaid);
            LocalDate receivedOn = received.getOrDefault(o.getId(), o.getOrderDate());
            if (balance.signum() <= 0 || receivedOn == null || receivedOn.isBefore(start)) {
                continue;
            }
            SupplierEntity supplier = suppliers.get(o.getSupplierId());
            int creditDays = supplier != null && supplier.getCreditDays() != null ? supplier.getCreditDays() : DEFAULT_CREDIT_DAYS;
            LocalDate due = receivedOn.plusDays(creditDays);
            int daysToDue = (int) ChronoUnit.DAYS.between(today, due);
            String name = supplier != null ? supplier.getName() : "Proveedor " + o.getSupplierId();
            rows.add(new PayableRow("O:" + o.getId(), "ORDEN", o.getId(), null, o.getCode(),
                    payeeKeyOf(o.getSupplierId(), null), o.getSupplierId(), name, o.getOrderDate(), receivedOn, due,
                    o.getTotal(), alreadyPaid, balance, statusOf(daysToDue, alreadyPaid), daysToDue,
                    supplier != null && supplier.getPayeeName() != null && !supplier.getPayeeName().isBlank()
                            ? supplier.getPayeeName() : name, null));
        }
        return rows;
    }

    /** Los documentos por pagar con saldo. No dependen de la fecha de inicio: se capturan a propósito. */
    private List<PayableRow> documentRows(LocalDate today) {
        List<PayableDocumentEntity> documents = documentRepository.findByStatus(PayableDocumentEntity.STATUS_ACTIVE);
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> paid = new HashMap<>();
        allocationRepository.paidByDocument().forEach(r -> paid.put(((Number) r[0]).longValue(), (BigDecimal) r[1]));
        Map<Long, SupplierEntity> suppliers = new HashMap<>();
        supplierRepository.findAllById(documents.stream().map(PayableDocumentEntity::getSupplierId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet())).forEach(s -> suppliers.put(s.getId(), s));
        List<PayableRow> rows = new ArrayList<>();
        for (PayableDocumentEntity d : documents) {
            BigDecimal alreadyPaid = paid.getOrDefault(d.getId(), BigDecimal.ZERO);
            BigDecimal balance = d.getAmount().subtract(alreadyPaid);
            if (balance.signum() <= 0) {
                continue;
            }
            SupplierEntity supplier = d.getSupplierId() != null ? suppliers.get(d.getSupplierId()) : null;
            int daysToDue = (int) ChronoUnit.DAYS.between(today, d.getDueDate());
            String kind = PayableDocumentEntity.KIND_RENT.equals(d.getKind()) ? "RENTA"
                    : PayableDocumentEntity.KIND_OPENING.equals(d.getKind()) ? "SALDO_INICIAL" : "DOCUMENTO";
            String name = supplier != null ? supplier.getName() : d.getPayeeName();
            rows.add(new PayableRow("D:" + d.getId(), kind, null, d.getId(),
                    d.getDocumentNumber() != null ? d.getDocumentNumber() : "DOC-" + d.getId(),
                    payeeKeyOf(d.getSupplierId(), d.getPayeeName()), d.getSupplierId(), name, d.getIssueDate(),
                    d.getIssueDate(), d.getDueDate(), d.getAmount(), alreadyPaid, balance,
                    statusOf(daysToDue, alreadyPaid), daysToDue, d.getPayeeName(), d.getConcept()));
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------
    // Pagos
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentsResponse listPayments() throws BusinessException {
        guard.assertCanView();
        List<SupplierPaymentEntity> payments = paymentRepository.findAllByOrderByIdDesc();
        return new PaymentsResponse(toRows(payments), guard.canPay());
    }

    /** Una partida ya validada que el pago va a cubrir. {@code rent} si el documento es una renta. */
    private record Target(String label, String payeeKey, Long supplierId, String payeeName,
                          SupplierPaymentAllocationEntity allocation, boolean rent) {}

    @Transactional
    public PaymentRow createPayment(CreateRequest request) throws BusinessException {
        Long userId = guard.assertCanPay();
        if (request == null || request.paymentMethodId() == null || request.payDate() == null) {
            throw new BusinessException("Indique la forma de pago y la fecha.");
        }
        if (request.allocations() == null || request.allocations().isEmpty()) {
            throw new BusinessException("Indique qué se paga.");
        }
        PaymentMethodEntity method = methodRepository.findById(request.paymentMethodId())
                .orElseThrow(() -> new BusinessException("La forma de pago no existe."));
        if (!Boolean.TRUE.equals(method.getActive())) {
            throw new BusinessException("La forma de pago «" + method.getName() + "» está inactiva.");
        }
        boolean printsCheque = Boolean.TRUE.equals(method.getPrintsCheque());

        BankAccountEntity account = null;
        if (request.bankAccountId() != null) {
            account = accountRepository.findById(request.bankAccountId())
                    .orElseThrow(() -> new BusinessException("La cuenta bancaria no existe."));
            if (!Boolean.TRUE.equals(account.getActive())) {
                throw new BusinessException("La cuenta «" + account.getName() + "» está inactiva.");
            }
        }
        if (Boolean.TRUE.equals(method.getRequiresBank()) && account == null) {
            throw new BusinessException("Elija la cuenta bancaria con la que se paga.");
        }
        if (printsCheque && account.getChequeLayoutId() == null) {
            throw new BusinessException("La cuenta «" + account.getName() + "» no tiene formato de cheque. Asígnelo en Bancos y formas de pago.");
        }
        String reference = request.reference() == null ? null : request.reference().trim();
        if (reference != null && reference.isEmpty()) {
            reference = null;
        }
        if (printsCheque && reference != null) {
            reference = PayrollRunService.compactCheque(reference);
        }
        if (Boolean.TRUE.equals(method.getRequiresReference()) && reference == null) {
            throw new BusinessException(printsCheque ? "Indique el número de cheque." : "Indique el número de boleta o la referencia.");
        }
        if (printsCheque && configService.isChequeUsed(account, reference)) {
            throw new BusinessException("El cheque " + reference + " ya se usó en esa chequera (los anulados tampoco se reutilizan).");
        }

        // Cada partida: ya vencida o por vencer, sin pasarse del saldo y del mismo beneficiario que las demás.
        List<AllocationInput> inputs = new ArrayList<>(request.allocations());
        inputs.sort(Comparator.comparing((AllocationInput a) -> a.purchaseOrderId() == null ? 1 : 0)
                .thenComparing(a -> a.purchaseOrderId() != null ? a.purchaseOrderId() : a.payableDocumentId()));
        Set<String> seen = new HashSet<>();
        List<Target> targets = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (AllocationInput in : inputs) {
            if (in.amount() == null || in.amount().signum() <= 0) {
                throw new BusinessException("Cada partida debe llevar un monto mayor que cero.");
            }
            if ((in.purchaseOrderId() == null) == (in.payableDocumentId() == null)) {
                throw new BusinessException("Cada partida debe ser una orden de compra o un documento por pagar.");
            }
            BigDecimal amount = in.amount().setScale(2, RoundingMode.HALF_UP);
            Target target = in.purchaseOrderId() != null
                    ? orderTarget(in.purchaseOrderId(), amount, seen)
                    : documentTarget(in.payableDocumentId(), amount, seen);
            targets.add(target);
            total = total.add(amount);
        }
        if (targets.stream().anyMatch(Target::rent) && targets.stream().anyMatch(target -> !target.rent())) {
            throw new BusinessException("Un pago no puede combinar rentas con pagos a proveedores.");
        }
        String payeeKey = targets.get(0).payeeKey();
        if (targets.stream().anyMatch(t -> !t.payeeKey().equals(payeeKey))) {
            throw new BusinessException("Solo se pueden juntar en un pago partidas de un mismo beneficiario.");
        }
        Long supplierId = targets.get(0).supplierId();
        String payee = request.payeeName() != null && !request.payeeName().isBlank() ? request.payeeName().trim()
                : targets.get(0).payeeName();
        List<String> labels = targets.stream().map(Target::label).toList();

        SupplierPaymentEntity payment = paymentRepository.save(SupplierPaymentEntity.builder()
                .supplierId(supplierId)
                .payeeKey(payeeKey)
                .paymentMethodId(method.getId())
                .bankAccountId(account != null ? account.getId() : null)
                .payDate(request.payDate())
                .amount(total)
                .payeeName(payee.toUpperCase(Locale.ROOT))
                .reference(reference)
                .concept(request.concept() == null || request.concept().isBlank() ? "Pago de " + String.join(", ", labels)
                        : request.concept().trim())
                .status(printsCheque ? SupplierPaymentEntity.STATUS_TO_PRINT : SupplierPaymentEntity.STATUS_PAID)
                .createdBy(userId)
                .build());
        payment.setPaymentNumber(String.format("PAG-%05d", payment.getId()));
        paymentRepository.save(payment);
        List<SupplierPaymentAllocationEntity> allocations = targets.stream().map(Target::allocation).toList();
        for (SupplierPaymentAllocationEntity a : allocations) {
            a.setPaymentId(payment.getId());
        }
        allocationRepository.saveAll(allocations);

        accountingService.postSupplierPayment(payment.getId(), total, account != null ? account.getLedgerCode() : null,
                account != null ? account.getLedgerName() : null, payment.getPaymentNumber(),
                "Pago a " + payee + " · " + String.join(", ", labels));
        return toRows(List.of(payment)).get(0);
    }

    private Target orderTarget(Long orderId, BigDecimal amount, Set<String> seen) throws BusinessException {
        if (!seen.add("O:" + orderId)) {
            throw new BusinessException("Una partida aparece más de una vez en el pago.");
        }
        // Las órdenes se bloquean para no cruzar pagos al mismo tiempo.
        PurchaseOrderEntity order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException("La orden " + orderId + " no existe."));
        if (order.getSupplierId() == null) {
            throw new BusinessException("La orden " + order.getCode() + " no tiene proveedor.");
        }
        if (!PAYABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException("La orden " + order.getCode() + " aún no se ha recibido.");
        }
        BigDecimal balance = order.getTotal().subtract(allocationRepository.paidForOrder(order.getId()));
        if (amount.compareTo(balance) > 0) {
            throw new BusinessException("La orden " + order.getCode() + " solo debe Q" + balance.setScale(2, RoundingMode.HALF_UP) + ".");
        }
        SupplierEntity supplier = supplierRepository.findById(order.getSupplierId()).orElse(null);
        String name = supplier != null ? supplier.getName() : "Proveedor " + order.getSupplierId();
        String payee = supplier != null && supplier.getPayeeName() != null && !supplier.getPayeeName().isBlank()
                ? supplier.getPayeeName() : name;
        return new Target(order.getCode(), payeeKeyOf(order.getSupplierId(), null), order.getSupplierId(), payee,
                SupplierPaymentAllocationEntity.builder().purchaseOrderId(order.getId()).amount(amount).build(), false);
    }

    private Target documentTarget(Long documentId, BigDecimal amount, Set<String> seen) throws BusinessException {
        if (!seen.add("D:" + documentId)) {
            throw new BusinessException("Una partida aparece más de una vez en el pago.");
        }
        PayableDocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("El documento " + documentId + " no existe."));
        if (!PayableDocumentEntity.STATUS_ACTIVE.equals(document.getStatus())) {
            throw new BusinessException("El documento " + documentId + " está anulado.");
        }
        BigDecimal balance = document.getAmount().subtract(allocationRepository.paidForDocument(document.getId()));
        String label = document.getDocumentNumber() != null ? document.getDocumentNumber() : document.getConcept();
        if (amount.compareTo(balance) > 0) {
            throw new BusinessException("«" + label + "» solo debe Q" + balance.setScale(2, RoundingMode.HALF_UP) + ".");
        }
        SupplierEntity supplier = document.getSupplierId() != null
                ? supplierRepository.findById(document.getSupplierId()).orElse(null) : null;
        String payee = supplier != null && supplier.getPayeeName() != null && !supplier.getPayeeName().isBlank()
                ? supplier.getPayeeName() : document.getPayeeName();
        return new Target(label, payeeKeyOf(document.getSupplierId(), document.getPayeeName()), document.getSupplierId(),
                payee, SupplierPaymentAllocationEntity.builder().payableDocumentId(document.getId()).amount(amount).build(),
                PayableDocumentEntity.KIND_RENT.equals(document.getKind()));
    }

    @Transactional
    public PaymentRow voidPayment(Long id, String reason) throws BusinessException {
        Long userId = guard.assertCanPay();
        SupplierPaymentEntity payment = paymentRepository.findById(id)
                .orElseThrow(() -> new BusinessException("El pago no existe."));
        if (SupplierPaymentEntity.STATUS_VOID.equals(payment.getStatus())) {
            throw new BusinessException("El pago ya está anulado.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Indique el motivo de la anulación.");
        }
        payment.setStatus(SupplierPaymentEntity.STATUS_VOID);
        payment.setVoidReason(reason.trim());
        payment.setVoidedAt(GuatemalaDateTime.now());
        payment.setVoidedBy(userId);
        paymentRepository.save(payment);
        accountingService.reverseSupplierPayment(payment.getId(), payment.getPaymentNumber());
        return toRows(List.of(payment)).get(0);
    }

    // ------------------------------------------------------------------------------------------------
    // Impresión de cheques
    // ------------------------------------------------------------------------------------------------

    /** Datos para dibujar los cheques. No cambia nada: el pago se marca impreso con {@link #markPrinted}. */
    @Transactional(readOnly = true)
    public List<ChequePrintItem> printData(List<Long> ids) throws BusinessException {
        guard.assertCanPay();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("Elija al menos un pago para imprimir.");
        }
        Map<Long, BankAccountEntity> accounts = new HashMap<>();
        accountRepository.findAll().forEach(a -> accounts.put(a.getId(), a));
        Map<Long, PaymentMethodEntity> methods = new HashMap<>();
        methodRepository.findAll().forEach(m -> methods.put(m.getId(), m));
        List<ChequePrintItem> items = new ArrayList<>();
        for (SupplierPaymentEntity p : paymentRepository.findAllById(ids)) {
            PaymentMethodEntity method = methods.get(p.getPaymentMethodId());
            if (method == null || !Boolean.TRUE.equals(method.getPrintsCheque())) {
                throw new BusinessException("El pago " + p.getPaymentNumber() + " no es con cheque.");
            }
            if (SupplierPaymentEntity.STATUS_VOID.equals(p.getStatus())) {
                throw new BusinessException("El pago " + p.getPaymentNumber() + " está anulado.");
            }
            BankAccountEntity account = accounts.get(p.getBankAccountId());
            if (account == null || account.getChequeLayoutId() == null) {
                throw new BusinessException("La cuenta del pago " + p.getPaymentNumber() + " no tiene formato de cheque.");
            }
            items.add(new ChequePrintItem(p.getId(), p.getPaymentNumber(), p.getPayeeName(), p.getAmount(), p.getPayDate(),
                    p.getReference(), p.getConcept(), p.getPrintCount() != null && p.getPrintCount() > 0,
                    configService.toDto(configService.findLayout(account.getChequeLayoutId()))));
        }
        items.sort(Comparator.comparing(ChequePrintItem::paymentId));
        return items;
    }

    @Transactional
    public PaymentsResponse markPrinted(List<Long> ids) throws BusinessException {
        Long userId = guard.assertCanPay();
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("Elija al menos un pago.");
        }
        for (SupplierPaymentEntity p : paymentRepository.findAllById(ids)) {
            if (SupplierPaymentEntity.STATUS_VOID.equals(p.getStatus())) {
                continue;
            }
            p.setStatus(SupplierPaymentEntity.STATUS_PRINTED);
            p.setPrintedAt(GuatemalaDateTime.now());
            p.setPrintedBy(userId);
            p.setPrintCount((p.getPrintCount() == null ? 0 : p.getPrintCount()) + 1);
            paymentRepository.save(p);
        }
        return new PaymentsResponse(toRows(paymentRepository.findAllByOrderByIdDesc()), true);
    }

    /** Verdadero solo si cada partida es un documento de renta. */
    private static boolean rentPayment(List<SupplierPaymentAllocationEntity> allocations, Set<Long> rentDocumentIds) {
        if (allocations == null || allocations.isEmpty()) {
            return false;
        }
        for (SupplierPaymentAllocationEntity allocation : allocations) {
            if (allocation.getPayableDocumentId() == null || !rentDocumentIds.contains(allocation.getPayableDocumentId())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------

    private List<PaymentRow> toRows(List<SupplierPaymentEntity> payments) {
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<Long, SupplierEntity> suppliers = new HashMap<>();
        supplierRepository.findAllById(payments.stream().map(SupplierPaymentEntity::getSupplierId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet())).forEach(s -> suppliers.put(s.getId(), s));
        Map<Long, PaymentMethodEntity> methods = new HashMap<>();
        methodRepository.findAll().forEach(m -> methods.put(m.getId(), m));
        Map<Long, BankAccountEntity> accounts = new HashMap<>();
        accountRepository.findAll().forEach(a -> accounts.put(a.getId(), a));

        List<Long> paymentIds = payments.stream().map(SupplierPaymentEntity::getId).toList();
        List<SupplierPaymentAllocationEntity> allocations = allocationRepository.findByPaymentIdIn(paymentIds);
        Map<Long, String> orderCodes = new HashMap<>();
        orderRepository.findAllById(allocations.stream().map(SupplierPaymentAllocationEntity::getPurchaseOrderId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet())).forEach(o -> orderCodes.put(o.getId(), o.getCode()));
        Map<Long, String> documentLabels = new HashMap<>();
        Set<Long> rentDocumentIds = new HashSet<>();
        documentRepository.findAllById(allocations.stream().map(SupplierPaymentAllocationEntity::getPayableDocumentId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .forEach(d -> {
                    documentLabels.put(d.getId(), d.getDocumentNumber() != null ? d.getDocumentNumber() : d.getConcept());
                    if (PayableDocumentEntity.KIND_RENT.equals(d.getKind())) {
                        rentDocumentIds.add(d.getId());
                    }
                });
        Map<Long, List<String>> codesByPayment = new HashMap<>();
        Map<Long, List<SupplierPaymentAllocationEntity>> allocationsByPayment = new HashMap<>();
        for (SupplierPaymentAllocationEntity a : allocations) {
            allocationsByPayment.computeIfAbsent(a.getPaymentId(), k -> new ArrayList<>()).add(a);
            String label = a.getPurchaseOrderId() != null ? orderCodes.get(a.getPurchaseOrderId())
                    : documentLabels.get(a.getPayableDocumentId());
            codesByPayment.computeIfAbsent(a.getPaymentId(), k -> new ArrayList<>()).add(label);
        }

        List<PaymentRow> rows = new ArrayList<>();
        for (SupplierPaymentEntity p : payments) {
            SupplierEntity supplier = p.getSupplierId() != null ? suppliers.get(p.getSupplierId()) : null;
            PaymentMethodEntity method = methods.get(p.getPaymentMethodId());
            BankAccountEntity account = p.getBankAccountId() != null ? accounts.get(p.getBankAccountId()) : null;
            rows.add(new PaymentRow(p.getId(), p.getPaymentNumber(), p.getSupplierId(),
                    supplier != null ? supplier.getName() : p.getPayeeName(), p.getPaymentMethodId(),
                    method != null ? method.getName() : "—", method != null && Boolean.TRUE.equals(method.getPrintsCheque()),
                    p.getBankAccountId(), account != null ? account.getName() : null, p.getPayDate(), p.getAmount(),
                    p.getPayeeName(), p.getReference(), p.getConcept(), p.getStatus(), p.getPrintedAt(),
                    p.getPrintCount() == null ? 0 : p.getPrintCount(), p.getVoidReason(),
                    codesByPayment.getOrDefault(p.getId(), List.of()), p.getCreatedAt(),
                    rentPayment(allocationsByPayment.get(p.getId()), rentDocumentIds)));
        }
        return rows;
    }
}
