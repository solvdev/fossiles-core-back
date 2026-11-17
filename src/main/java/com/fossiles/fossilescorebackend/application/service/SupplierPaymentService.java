package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.ChequePrintItem;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayableRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesSummary;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentRow;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PaymentsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BankAccountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentAllocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BankAccountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialReceiptRepository;
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
 * Cuentas por pagar y pagos a proveedores. Lo que se debe de cada orden recibida es su total menos los pagos que no se
 * anularon; un pago puede juntar varias órdenes del mismo proveedor y cubrir solo una parte de cada una. Cada pago
 * genera su partida contable (cuentas por pagar contra el banco) y, al anularse, la invierte.
 */
@Service
@RequiredArgsConstructor
public class SupplierPaymentService {

    /** Órdenes que ya se recibieron, total o parcialmente: son las que se le deben al proveedor. */
    static final List<String> PAYABLE_STATUSES = List.of("RECIBIDA", "PARCIALMENTE_RECIBIDA");
    static final int DEFAULT_CREDIT_DAYS = 30;
    /** Una orden que vence en estos días o menos aparece «por vencer». */
    static final int DUE_SOON_DAYS = 15;

    private final PurchaseOrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final MaterialReceiptRepository receiptRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final PaymentMethodRepository methodRepository;
    private final BankAccountRepository accountRepository;
    private final SupplierPaymentConfigService configService;
    private final AccountingService accountingService;
    private final SupplierPaymentAccessGuard guard;

    public record AllocationInput(Long purchaseOrderId, BigDecimal amount) {}

    public record CreateRequest(Long supplierId, Long paymentMethodId, Long bankAccountId, LocalDate payDate,
                                String payeeName, String reference, String concept, List<AllocationInput> allocations) {}

    // ------------------------------------------------------------------------------------------------
    // Cuentas por pagar
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PayablesResponse payables() throws BusinessException {
        guard.assertCanView();
        LocalDate today = GuatemalaDateTime.today();
        List<PayableRow> rows = buildPayables(today);

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal in7 = BigDecimal.ZERO;
        int overdueCount = 0;
        int dueSoon = 0;
        int in7Count = 0;
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
        }
        LocalDate monthStart = today.withDayOfMonth(1);
        BigDecimal paidMonth = BigDecimal.ZERO;
        int paymentsMonth = 0;
        for (SupplierPaymentEntity p : paymentRepository.findAll()) {
            if (!SupplierPaymentEntity.STATUS_VOID.equals(p.getStatus()) && !p.getPayDate().isBefore(monthStart)) {
                paidMonth = paidMonth.add(p.getAmount());
                paymentsMonth++;
            }
        }
        Set<Long> suppliers = rows.stream().map(PayableRow::supplierId).collect(Collectors.toSet());
        PayablesSummary summary = new PayablesSummary(total, rows.size(), suppliers.size(), overdue, overdueCount,
                dueSoon, in7, in7Count, paidMonth, paymentsMonth);
        return new PayablesResponse(today, summary, rows, guard.canPay());
    }

    List<PayableRow> buildPayables(LocalDate today) {
        LocalDate start = configService.payablesStartDate();
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
            String status = daysToDue < 0 ? "VENCIDA"
                    : daysToDue <= DUE_SOON_DAYS ? "POR_VENCER"
                    : alreadyPaid.signum() > 0 ? "PARCIAL" : "AL_DIA";
            rows.add(new PayableRow(o.getId(), o.getCode(), o.getSupplierId(),
                    supplier != null ? supplier.getName() : "Proveedor " + o.getSupplierId(), o.getOrderDate(),
                    receivedOn, due, o.getTotal(), alreadyPaid, balance, status, daysToDue));
        }
        rows.sort(Comparator.comparing(PayableRow::supplierName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PayableRow::dueDate));
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

    @Transactional
    public PaymentRow createPayment(CreateRequest request) throws BusinessException {
        Long userId = guard.assertCanPay();
        if (request == null || request.supplierId() == null || request.paymentMethodId() == null || request.payDate() == null) {
            throw new BusinessException("Indique el proveedor, la forma de pago y la fecha.");
        }
        if (request.allocations() == null || request.allocations().isEmpty()) {
            throw new BusinessException("Indique qué órdenes se pagan.");
        }
        SupplierEntity supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new BusinessException("El proveedor no existe."));
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

        // Cada orden: del mismo proveedor, ya recibida y sin pasarse del saldo. Se bloquean en orden para no cruzar pagos.
        List<AllocationInput> inputs = new ArrayList<>(request.allocations());
        inputs.sort(Comparator.comparing(AllocationInput::purchaseOrderId));
        Set<Long> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        List<String> codes = new ArrayList<>();
        List<SupplierPaymentAllocationEntity> allocations = new ArrayList<>();
        for (AllocationInput in : inputs) {
            if (in.purchaseOrderId() == null || in.amount() == null || in.amount().signum() <= 0) {
                throw new BusinessException("Cada orden debe llevar un monto mayor que cero.");
            }
            if (!seen.add(in.purchaseOrderId())) {
                throw new BusinessException("Una orden aparece más de una vez en el pago.");
            }
            PurchaseOrderEntity order = orderRepository.findByIdForUpdate(in.purchaseOrderId())
                    .orElseThrow(() -> new BusinessException("La orden " + in.purchaseOrderId() + " no existe."));
            if (!supplier.getId().equals(order.getSupplierId())) {
                throw new BusinessException("La orden " + order.getCode() + " no es de " + supplier.getName() + ".");
            }
            if (!PAYABLE_STATUSES.contains(order.getStatus())) {
                throw new BusinessException("La orden " + order.getCode() + " aún no se ha recibido.");
            }
            BigDecimal amount = in.amount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal balance = order.getTotal().subtract(allocationRepository.paidForOrder(order.getId()));
            if (amount.compareTo(balance) > 0) {
                throw new BusinessException("La orden " + order.getCode() + " solo debe Q" + balance.setScale(2, RoundingMode.HALF_UP) + ".");
            }
            total = total.add(amount);
            codes.add(order.getCode());
            allocations.add(SupplierPaymentAllocationEntity.builder().purchaseOrderId(order.getId()).amount(amount).build());
        }

        String payee = request.payeeName() != null && !request.payeeName().isBlank() ? request.payeeName().trim()
                : supplier.getPayeeName() != null && !supplier.getPayeeName().isBlank() ? supplier.getPayeeName() : supplier.getName();
        SupplierPaymentEntity payment = paymentRepository.save(SupplierPaymentEntity.builder()
                .supplierId(supplier.getId())
                .paymentMethodId(method.getId())
                .bankAccountId(account != null ? account.getId() : null)
                .payDate(request.payDate())
                .amount(total)
                .payeeName(payee.toUpperCase(Locale.ROOT))
                .reference(reference)
                .concept(request.concept() == null || request.concept().isBlank() ? "Pago de " + String.join(", ", codes)
                        : request.concept().trim())
                .status(printsCheque ? SupplierPaymentEntity.STATUS_TO_PRINT : SupplierPaymentEntity.STATUS_PAID)
                .createdBy(userId)
                .build());
        payment.setPaymentNumber(String.format("PAG-%05d", payment.getId()));
        paymentRepository.save(payment);
        for (SupplierPaymentAllocationEntity a : allocations) {
            a.setPaymentId(payment.getId());
        }
        allocationRepository.saveAll(allocations);

        accountingService.postSupplierPayment(payment.getId(), total, account != null ? account.getLedgerCode() : null,
                account != null ? account.getLedgerName() : null, payment.getPaymentNumber(),
                "Pago a proveedor " + supplier.getName() + " · " + String.join(", ", codes));
        return toRows(List.of(payment)).get(0);
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

    // ------------------------------------------------------------------------------------------------

    private List<PaymentRow> toRows(List<SupplierPaymentEntity> payments) {
        if (payments.isEmpty()) {
            return List.of();
        }
        Map<Long, SupplierEntity> suppliers = new HashMap<>();
        supplierRepository.findAllById(payments.stream().map(SupplierPaymentEntity::getSupplierId).collect(Collectors.toSet()))
                .forEach(s -> suppliers.put(s.getId(), s));
        Map<Long, PaymentMethodEntity> methods = new HashMap<>();
        methodRepository.findAll().forEach(m -> methods.put(m.getId(), m));
        Map<Long, BankAccountEntity> accounts = new HashMap<>();
        accountRepository.findAll().forEach(a -> accounts.put(a.getId(), a));

        List<Long> paymentIds = payments.stream().map(SupplierPaymentEntity::getId).toList();
        List<SupplierPaymentAllocationEntity> allocations = allocationRepository.findByPaymentIdIn(paymentIds);
        Map<Long, String> orderCodes = new HashMap<>();
        orderRepository.findAllById(allocations.stream().map(SupplierPaymentAllocationEntity::getPurchaseOrderId)
                .collect(Collectors.toSet())).forEach(o -> orderCodes.put(o.getId(), o.getCode()));
        Map<Long, List<String>> codesByPayment = new HashMap<>();
        for (SupplierPaymentAllocationEntity a : allocations) {
            codesByPayment.computeIfAbsent(a.getPaymentId(), k -> new ArrayList<>()).add(orderCodes.get(a.getPurchaseOrderId()));
        }

        List<PaymentRow> rows = new ArrayList<>();
        for (SupplierPaymentEntity p : payments) {
            SupplierEntity supplier = suppliers.get(p.getSupplierId());
            PaymentMethodEntity method = methods.get(p.getPaymentMethodId());
            BankAccountEntity account = p.getBankAccountId() != null ? accounts.get(p.getBankAccountId()) : null;
            rows.add(new PaymentRow(p.getId(), p.getPaymentNumber(), p.getSupplierId(),
                    supplier != null ? supplier.getName() : "Proveedor " + p.getSupplierId(), p.getPaymentMethodId(),
                    method != null ? method.getName() : "—", method != null && Boolean.TRUE.equals(method.getPrintsCheque()),
                    p.getBankAccountId(), account != null ? account.getName() : null, p.getPayDate(), p.getAmount(),
                    p.getPayeeName(), p.getReference(), p.getConcept(), p.getStatus(), p.getPrintedAt(),
                    p.getPrintCount() == null ? 0 : p.getPrintCount(), p.getVoidReason(),
                    codesByPayment.getOrDefault(p.getId(), List.of()), p.getCreatedAt()));
        }
        return rows;
    }
}
