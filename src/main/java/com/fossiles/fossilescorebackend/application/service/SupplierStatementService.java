package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayableDocumentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PaymentMethodEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentAllocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierPaymentEntity;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estado de cuenta de proveedores (el «Pagos generales» del sistema anterior): por proveedor, cada orden recibida y
 * cada documento por pagar con sus cargos, abonos y saldo a una fecha de corte. Los abonos son los pagos no anulados
 * con fecha hasta el corte.
 */
@Service
@RequiredArgsConstructor
public class SupplierStatementService {

    public static final String KIND_ALL = "ALL";
    public static final String KIND_ORDERS = "ORDERS";
    public static final String KIND_DOCUMENTS = "DOCUMENTS";
    public static final String KIND_OPENING = "OPENING";
    public static final String KIND_RENTS = "RENTS";
    private static final Set<String> KINDS = Set.of(KIND_ALL, KIND_ORDERS, KIND_DOCUMENTS, KIND_OPENING, KIND_RENTS);

    private final PurchaseOrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final MaterialReceiptRepository receiptRepository;
    private final SupplierPaymentRepository paymentRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final PayableDocumentRepository documentRepository;
    private final PaymentMethodRepository methodRepository;
    private final SupplierPaymentConfigService configService;
    private final SupplierPaymentService paymentService;
    private final SupplierPaymentAccessGuard guard;

    public record StatementQuery(String fromCode, String toCode, LocalDate cutoff, String kind, boolean onlyBalance,
                                 boolean includeNoCode) {}

    /** Un abono aplicado: «Cheque 1046» y su número de pago. */
    public record StatementPayment(Long paymentId, String number, String label, LocalDate payDate, BigDecimal amount) {}

    /**
     * {@code kind}: ORDEN, DOCUMENTO, SALDO_INICIAL o RENTA. {@code document}: la factura del proveedor (o el código de la
     * orden si no tiene factura); {@code orderCode} va aparte. {@code daysLate} &gt; 0 = vencido a la fecha de corte.
     */
    public record StatementLine(String rowKey, String kind, String concept, String document, String orderCode,
                                LocalDate issueDate, LocalDate dueDate, BigDecimal charges, BigDecimal credits,
                                BigDecimal balance, int daysLate, List<StatementPayment> payments) {}

    public record StatementGroup(String key, Long supplierId, String code, String name, String payeeName, String phone,
                                 String nit, Integer creditDays, List<StatementLine> lines, BigDecimal charges,
                                 BigDecimal credits, BigDecimal balance) {}

    public record StatementResponse(LocalDate cutoff, String fromCode, String toCode, String kind, boolean onlyBalance,
                                    LocalDate payablesStartDate, List<StatementGroup> groups, int documentCount,
                                    BigDecimal charges, BigDecimal credits, BigDecimal balance) {}

    @Transactional(readOnly = true)
    public StatementResponse statement(StatementQuery query) throws BusinessException {
        guard.assertCanView();
        LocalDate cutoff = query.cutoff() != null ? query.cutoff() : GuatemalaDateTime.today();
        String kind = query.kind() == null || query.kind().isBlank() ? KIND_ALL : query.kind().trim().toUpperCase();
        if (!KINDS.contains(kind)) {
            throw new BusinessException("Tipo de documento no válido: " + query.kind());
        }
        String from = blankToNull(query.fromCode());
        String to = blankToNull(query.toCode());
        int fromN = from == null ? Integer.MIN_VALUE : SupplierCodeService.numberOf(from);
        int toN = to == null ? Integer.MAX_VALUE : SupplierCodeService.numberOf(to);
        if (fromN == -1 || toN == -1) {
            throw new BusinessException("El rango debe ser de códigos PV, por ejemplo del PV001 al PV167.");
        }
        if (fromN > toN) {
            int swap = fromN;
            fromN = toN;
            toN = swap;
        }

        Map<Long, SupplierEntity> suppliers = new HashMap<>();
        supplierRepository.findAll().forEach(s -> suppliers.put(s.getId(), s));
        Map<Long, List<StatementPayment>> byOrder = new HashMap<>();
        Map<Long, List<StatementPayment>> byDocument = new HashMap<>();
        paymentsUntil(cutoff, byOrder, byDocument);

        Map<String, GroupBuilder> groups = new LinkedHashMap<>();
        LocalDate start = configService.payablesStartDate();
        if (KIND_ALL.equals(kind) || KIND_ORDERS.equals(kind)) {
            addOrders(groups, suppliers, byOrder, cutoff, start);
        }
        if (!KIND_ORDERS.equals(kind)) {
            addDocuments(groups, suppliers, byDocument, cutoff, kind);
        }

        List<StatementGroup> out = new ArrayList<>();
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        int documents = 0;
        for (GroupBuilder g : groups.values()) {
            int n = SupplierCodeService.numberOf(g.code);
            boolean inRange = n >= 0 ? n >= fromN && n <= toN : query.includeNoCode();
            if (!inRange) continue;
            List<StatementLine> lines = g.lines.stream()
                    .filter(l -> !query.onlyBalance() || l.balance().signum() != 0)
                    .sorted(Comparator.comparing(StatementLine::issueDate).thenComparing(StatementLine::document,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
            if (lines.isEmpty()) continue;
            BigDecimal c = lines.stream().map(StatementLine::charges).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal a = lines.stream().map(StatementLine::credits).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new StatementGroup(g.key, g.supplierId, g.code, g.name, g.payeeName, g.phone, g.nit, g.creditDays,
                    lines, c, a, c.subtract(a)));
            charges = charges.add(c);
            credits = credits.add(a);
            documents += lines.size();
        }
        out.sort(Comparator.comparingInt((StatementGroup g) -> {
                    int n = SupplierCodeService.numberOf(g.code());
                    return n < 0 ? Integer.MAX_VALUE : n;
                })
                .thenComparing(StatementGroup::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return new StatementResponse(cutoff, from == null ? null : SupplierCodeService.normalize(from),
                to == null ? null : SupplierCodeService.normalize(to), kind, query.onlyBalance(), start, out, documents,
                charges, credits, charges.subtract(credits));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class GroupBuilder {
        String key;
        Long supplierId;
        String code;
        String name;
        String payeeName;
        String phone;
        String nit;
        Integer creditDays;
        final List<StatementLine> lines = new ArrayList<>();
    }

    private static GroupBuilder group(Map<String, GroupBuilder> groups, SupplierEntity supplier, Long supplierId, String payee) {
        String key = SupplierPaymentService.payeeKeyOf(supplierId, payee);
        return groups.computeIfAbsent(key, k -> {
            GroupBuilder g = new GroupBuilder();
            g.key = k;
            g.supplierId = supplierId;
            if (supplier != null) {
                g.code = supplier.getCode();
                g.name = supplier.getName();
                g.payeeName = supplier.getPayeeName() != null && !supplier.getPayeeName().isBlank()
                        && !supplier.getPayeeName().equalsIgnoreCase(supplier.getName()) ? supplier.getPayeeName() : null;
                g.phone = supplier.getPhone();
                g.nit = supplier.getNit();
                g.creditDays = supplier.getCreditDays();
            } else {
                g.name = payee != null ? payee : "Proveedor " + supplierId;
            }
            return g;
        });
    }

    private void paymentsUntil(LocalDate cutoff, Map<Long, List<StatementPayment>> byOrder,
                               Map<Long, List<StatementPayment>> byDocument) {
        Map<Long, SupplierPaymentEntity> payments = new HashMap<>();
        for (SupplierPaymentEntity p : paymentRepository.findAll()) {
            if (!SupplierPaymentEntity.STATUS_VOID.equals(p.getStatus()) && !p.getPayDate().isAfter(cutoff)) {
                payments.put(p.getId(), p);
            }
        }
        if (payments.isEmpty()) return;
        Map<Long, String> methods = new HashMap<>();
        for (PaymentMethodEntity m : methodRepository.findAll()) methods.put(m.getId(), m.getName());
        for (SupplierPaymentAllocationEntity a : allocationRepository.findByPaymentIdIn(payments.keySet())) {
            SupplierPaymentEntity p = payments.get(a.getPaymentId());
            String method = methods.getOrDefault(p.getPaymentMethodId(), "Pago");
            String label = p.getReference() != null && !p.getReference().isBlank() ? method + " " + p.getReference() : method;
            StatementPayment sp = new StatementPayment(p.getId(), p.getPaymentNumber(), label, p.getPayDate(), a.getAmount());
            if (a.getPurchaseOrderId() != null) {
                byOrder.computeIfAbsent(a.getPurchaseOrderId(), k -> new ArrayList<>()).add(sp);
            } else if (a.getPayableDocumentId() != null) {
                byDocument.computeIfAbsent(a.getPayableDocumentId(), k -> new ArrayList<>()).add(sp);
            }
        }
    }

    private void addOrders(Map<String, GroupBuilder> groups, Map<Long, SupplierEntity> suppliers,
                           Map<Long, List<StatementPayment>> paid, LocalDate cutoff, LocalDate start) {
        List<PurchaseOrderEntity> orders = new ArrayList<>();
        for (String status : SupplierPaymentService.PAYABLE_STATUSES) {
            orders.addAll(orderRepository.findByStatus(status));
        }
        orders.removeIf(o -> o.getTotal() == null || o.getTotal().signum() <= 0 || o.getSupplierId() == null);
        if (orders.isEmpty()) return;
        List<Long> ids = orders.stream().map(PurchaseOrderEntity::getId).toList();
        Map<Long, LocalDate> received = new HashMap<>();
        receiptRepository.findLastReceiptDates(ids).forEach(r -> received.put(((Number) r[0]).longValue(), (LocalDate) r[1]));
        Map<Long, SupplierPaymentService.InvoiceInfo> invoices = paymentService.invoicesByOrder(ids);
        for (PurchaseOrderEntity o : orders) {
            LocalDate receivedOn = received.getOrDefault(o.getId(), o.getOrderDate());
            if (receivedOn == null || receivedOn.isBefore(start)) continue;
            SupplierPaymentService.InvoiceInfo invoice = invoices.get(o.getId());
            LocalDate issue = invoice != null && invoice.latestDate() != null ? invoice.latestDate() : receivedOn;
            if (issue.isAfter(cutoff)) continue;
            SupplierEntity supplier = suppliers.get(o.getSupplierId());
            LocalDate due = SupplierPaymentService.dueDateOf(invoice, receivedOn, supplier);
            List<StatementPayment> payments = paid.getOrDefault(o.getId(), List.of());
            BigDecimal credits = sum(payments);
            group(groups, supplier, o.getSupplierId(), null).lines.add(new StatementLine("O:" + o.getId(), "ORDEN", "Compras",
                    invoice != null ? invoice.label() : o.getCode(), o.getCode(), issue, due, o.getTotal(), credits,
                    o.getTotal().subtract(credits), (int) ChronoUnit.DAYS.between(due, cutoff), payments));
        }
    }

    private void addDocuments(Map<String, GroupBuilder> groups, Map<Long, SupplierEntity> suppliers,
                              Map<Long, List<StatementPayment>> paid, LocalDate cutoff, String kind) {
        for (PayableDocumentEntity d : documentRepository.findByStatus(PayableDocumentEntity.STATUS_ACTIVE)) {
            String lineKind = PayableDocumentEntity.KIND_RENT.equals(d.getKind()) ? "RENTA"
                    : PayableDocumentEntity.KIND_OPENING.equals(d.getKind()) ? "SALDO_INICIAL" : "DOCUMENTO";
            boolean wanted = switch (kind) {
                case KIND_DOCUMENTS -> "DOCUMENTO".equals(lineKind);
                case KIND_OPENING -> "SALDO_INICIAL".equals(lineKind);
                case KIND_RENTS -> "RENTA".equals(lineKind);
                default -> true;
            };
            if (!wanted || d.getIssueDate().isAfter(cutoff)) continue;
            SupplierEntity supplier = d.getSupplierId() != null ? suppliers.get(d.getSupplierId()) : null;
            List<StatementPayment> payments = paid.getOrDefault(d.getId(), List.of());
            BigDecimal credits = sum(payments);
            group(groups, supplier, d.getSupplierId(), d.getPayeeName()).lines.add(new StatementLine("D:" + d.getId(),
                    lineKind, d.getConcept(), d.getDocumentNumber() != null ? d.getDocumentNumber() : "DOC-" + d.getId(),
                    null, d.getIssueDate(), d.getDueDate(), d.getAmount(), credits, d.getAmount().subtract(credits),
                    (int) ChronoUnit.DAYS.between(d.getDueDate(), cutoff), payments));
        }
    }

    private static BigDecimal sum(List<StatementPayment> payments) {
        return payments.stream().map(StatementPayment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
