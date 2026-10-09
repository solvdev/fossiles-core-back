package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioCustomerResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountPortfolioRowResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerAccountEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reporte de cartera de clientes (impresión RUTAS CxC): una fila por documento con cargos, pagos,
 * créditos y saldo, calculado desde el libro de cuentas por cobrar con la misma regla que el listado
 * ({@link CustomerAccountService#getSummary}), de modo que los totales cuadran con los saldos del sistema.
 *
 * <p>Es una cartera de saldos: un documento cuyo saldo es cero ya no se debe y no se lista, y un cliente sin
 * ningún documento con saldo tampoco. Los documentos con saldo negativo (crédito a favor) sí se listan.
 *
 * <p>El detalle de movimientos es un anexo separado y opcional; nunca se mezcla con las filas de cartera.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerAccountPortfolioReportService {

    static final String ROW_DOCUMENT = "DOCUMENT";
    static final String ROW_OPENING_BALANCE = "OPENING_BALANCE";
    static final String ROW_ORPHAN_CREDIT = "ORPHAN_CREDIT";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String TYPE_CHARGE = "CHARGE";
    private static final String TYPE_PAYMENT = "PAYMENT";
    private static final String TYPE_CREDIT_NOTE = "CREDIT_NOTE";
    private static final String TYPE_OPENING_BALANCE = "OPENING_BALANCE";
    private static final String TYPE_CHARGE_ADJUSTMENT = "CHARGE_ADJUSTMENT";
    private static final String TYPE_RETURN = "RETURN";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    /** Por debajo de medio centavo un saldo es cero (los montos se guardan a 2 decimales). */
    private static final BigDecimal BALANCE_EPSILON = new BigDecimal("0.005");

    private final CustomerAccountService accountService;
    private final CustomerAccountEntryRepository entryRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductShipmentRepository productShipmentRepository;

    public CustomerAccountPortfolioReportResponse buildReport(
            String search,
            String orderKind,
            String regionCode,
            Integer routeNumber,
            String routeLocationCode,
            boolean includeMovements,
            LocalDate movementsFrom,
            LocalDate movementsTo) throws BusinessException {
        String kind = normalizeKind(orderKind);
        String searchNorm = search != null ? search.trim().toLowerCase(Locale.ROOT) : "";

        List<CustomerAccountSummaryResponse> summaries = accountService.getSummary(
                null, true, false, regionCode, routeNumber, routeLocationCode);
        Set<Long> customerIds = summaries.stream()
                .map(CustomerAccountSummaryResponse::getCustomerId)
                .collect(Collectors.toSet());

        // Una sola lectura del libro (sin joins ni paginación): cada movimiento aparece una vez.
        Map<Long, List<CustomerAccountEntryEntity>> entriesByCustomer = entryRepository.findAll().stream()
                .filter(e -> customerIds.contains(e.getCustomerId()))
                .collect(Collectors.groupingBy(CustomerAccountEntryEntity::getCustomerId));

        Map<Long, ProductionOrderEntity> ordersById = loadOrders(entriesByCustomer);
        Map<Long, ProductShipmentEntity> shipmentsById = loadShipments(entriesByCustomer);

        List<CustomerAccountPortfolioRowResponse> rows = new ArrayList<>();
        List<CustomerAccountPortfolioCustomerResponse> customers = new ArrayList<>();
        List<CustomerAccountPortfolioMovementResponse> movements = new ArrayList<>();
        BigDecimal unappliedCredits = ZERO;
        BigDecimal reconDue = ZERO;
        BigDecimal systemDue = ZERO;
        int duplicateDocs = 0;

        for (CustomerAccountSummaryResponse summary : summaries) {
            List<CustomerAccountEntryEntity> entries = entriesByCustomer.getOrDefault(summary.getCustomerId(), List.of());
            List<CustomerAccountEntryEntity> active = entries.stream()
                    .filter(e -> STATUS_ACTIVE.equalsIgnoreCase(e.getStatus()))
                    .toList();

            List<CustomerAccountPortfolioRowResponse> customerRows = buildCustomerRows(
                    summary, active, kind, ordersById, shipmentsById);
            if (customerRows.isEmpty()) {
                continue;
            }

            boolean customerMatches = searchNorm.isEmpty() || customerMatchesSearch(summary, searchNorm);
            List<CustomerAccountPortfolioRowResponse> matching = customerRows.stream()
                    .filter(r -> customerMatches || rowMatchesSearch(r, searchNorm))
                    .toList();
            if (matching.isEmpty()) {
                continue;
            }
            // Cartera = lo que todavía se debe: los documentos saldados (saldo cero) no se listan y un cliente
            // sin documentos con saldo no sale. Quitarlos no cambia el saldo neto del cliente.
            List<CustomerAccountPortfolioRowResponse> filtered = matching.stream()
                    .filter(CustomerAccountPortfolioReportService::hasBalance)
                    .toList();
            CustomerAccountPortfolioCustomerResponse customerTotals = totalsFor(summary, filtered);

            // Si la búsqueda ocultó documentos, el saldo del cliente ya no es comparable con el del sistema.
            // La conciliación incluye también a los clientes en cero: así un saldo en cero aquí que el sistema
            // todavía muestra como deuda sigue apareciendo como diferencia en vez de esconderse.
            boolean subset = matching.size() != customerRows.size();
            if (!subset) {
                reconDue = reconDue.add(customerTotals.getBalanceDue());
                BigDecimal due = "OPC".equals(kind) ? summary.getBalanceDueOpc() : summary.getBalanceDueOpv();
                systemDue = systemDue.add(due != null ? due : ZERO);
            }
            if (filtered.isEmpty()) {
                continue;
            }

            rows.addAll(filtered);
            customers.add(customerTotals);
            duplicateDocs += (int) filtered.stream().filter(CustomerAccountPortfolioRowResponse::isDuplicateCharges).count();

            for (CustomerAccountEntryEntity credit : active) {
                if (isCredit(credit.getEntryType()) && credit.getAppliedToEntryId() == null) {
                    unappliedCredits = unappliedCredits.add(accountService.resolveEntryAppliedCredit(credit));
                }
            }

            if (includeMovements) {
                movements.addAll(buildMovements(summary, entries, kind, ordersById, movementsFrom, movementsTo));
            }
        }

        movements.sort(Comparator
                .comparing((CustomerAccountPortfolioMovementResponse m) -> Objects.toString(m.getCustomerName(), ""),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CustomerAccountPortfolioMovementResponse::getEntryDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CustomerAccountPortfolioMovementResponse::getEntryId));

        BigDecimal totalCharged = sum(customers, CustomerAccountPortfolioCustomerResponse::getChargedAmount);
        BigDecimal totalPayments = sum(customers, CustomerAccountPortfolioCustomerResponse::getPaymentsApplied);
        BigDecimal totalCredits = sum(customers, CustomerAccountPortfolioCustomerResponse::getCreditsApplied);
        BigDecimal totalDue = sum(customers, CustomerAccountPortfolioCustomerResponse::getBalanceDue);
        BigDecimal totalCreditBalance = sum(customers, CustomerAccountPortfolioCustomerResponse::getCreditBalance);
        BigDecimal difference = reconDue.subtract(systemDue).setScale(2, RoundingMode.HALF_UP);

        return CustomerAccountPortfolioReportResponse.builder()
                .generatedAt(LocalDateTime.now())
                .orderKind(kind)
                .movementsFrom(movementsFrom)
                .movementsTo(movementsTo)
                .rows(rows)
                .customers(customers)
                .customerCount(customers.size())
                .documentCount((int) rows.stream().filter(r -> !ROW_ORPHAN_CREDIT.equals(r.getRowType())).count())
                .totalCharged(totalCharged)
                .totalPayments(totalPayments)
                .totalCredits(totalCredits)
                .totalBalanceDue(totalDue)
                .totalCreditBalance(totalCreditBalance)
                .systemBalanceDue(systemDue.setScale(2, RoundingMode.HALF_UP))
                .difference(difference)
                .reconciled(difference.compareTo(BigDecimal.ZERO) == 0)
                .unappliedCreditsTotal(unappliedCredits.setScale(2, RoundingMode.HALF_UP))
                .duplicateChargeDocuments(duplicateDocs)
                .includesMovements(includeMovements)
                .movements(includeMovements ? movements : List.of())
                .build();
    }

    // ---- filas por documento -------------------------------------------------------------

    private List<CustomerAccountPortfolioRowResponse> buildCustomerRows(
            CustomerAccountSummaryResponse summary,
            List<CustomerAccountEntryEntity> active,
            String kind,
            Map<Long, ProductionOrderEntity> ordersById,
            Map<Long, ProductShipmentEntity> shipmentsById) {
        List<CustomerAccountEntryEntity> charges = active.stream()
                .filter(e -> TYPE_CHARGE.equalsIgnoreCase(e.getEntryType()))
                .sorted(Comparator
                        .comparingInt(CustomerAccountPortfolioReportService::chargeLevel)
                        .thenComparing(CustomerAccountEntryEntity::getEntryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CustomerAccountEntryEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Un cargo cubierto por otro del mismo documento (orden > parcial > envío) es el mismo documento:
        // se consolida en una sola fila y se marca para que el usuario anule el sobrante.
        List<ChargeGroup> groups = new ArrayList<>();
        Map<Long, ChargeGroup> groupByChargeId = new HashMap<>();
        for (CustomerAccountEntryEntity charge : charges) {
            ChargeGroup target = null;
            if (charge.getProductionOrderId() != null) {
                for (ChargeGroup group : groups) {
                    if (covers(group.head, charge)) {
                        target = group;
                        break;
                    }
                }
            }
            if (target == null) {
                target = new ChargeGroup(charge);
                groups.add(target);
            } else {
                target.charges.add(charge);
            }
            groupByChargeId.put(charge.getId(), target);
        }

        List<CustomerAccountEntryEntity> orphanCredits = new ArrayList<>();
        List<CustomerAccountEntryEntity> looseAdjustments = new ArrayList<>();
        for (CustomerAccountEntryEntity entry : active) {
            if (!TYPE_CHARGE_ADJUSTMENT.equalsIgnoreCase(entry.getEntryType())) {
                continue;
            }
            ChargeGroup group = entry.getAppliedToEntryId() == null
                    ? null
                    : groupByChargeId.get(entry.getAppliedToEntryId());
            if (group != null) {
                group.adjustments.add(entry);
            } else {
                looseAdjustments.add(entry);
            }
        }
        for (CustomerAccountEntryEntity entry : active) {
            if (!isCredit(entry.getEntryType()) || entry.getAppliedToEntryId() == null) {
                continue;
            }
            ChargeGroup group = groupByChargeId.get(entry.getAppliedToEntryId());
            if (group != null) {
                group.credits.add(entry);
            } else {
                orphanCredits.add(entry);
            }
        }

        List<CustomerAccountPortfolioRowResponse> rows = new ArrayList<>();
        for (ChargeGroup group : groups) {
            if (kind.equals(kindOf(group.head, ordersById))) {
                rows.add(documentRow(summary, group, ordersById, shipmentsById));
            }
        }
        for (CustomerAccountEntryEntity opening : active) {
            if (TYPE_OPENING_BALANCE.equalsIgnoreCase(opening.getEntryType())
                    && kind.equals(kindOf(opening, ordersById))) {
                rows.add(openingRow(summary, opening, kind));
            }
        }
        List<CustomerAccountEntryEntity> kindOrphans = orphanCredits.stream()
                .filter(e -> kind.equals(kindOf(e, ordersById)))
                .toList();
        if (!kindOrphans.isEmpty()) {
            rows.add(orphanCreditRow(summary, kindOrphans, kind));
        }
        for (CustomerAccountEntryEntity adjustment : looseAdjustments) {
            if (kind.equals(kindOf(adjustment, ordersById))) {
                rows.add(adjustmentRow(summary, adjustment, kind));
            }
        }

        rows.sort(Comparator
                .comparing(CustomerAccountPortfolioRowResponse::getChargeDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CustomerAccountPortfolioRowResponse::getChargeEntryId, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    private CustomerAccountPortfolioRowResponse documentRow(
            CustomerAccountSummaryResponse summary,
            ChargeGroup group,
            Map<Long, ProductionOrderEntity> ordersById,
            Map<Long, ProductShipmentEntity> shipmentsById) {
        CustomerAccountEntryEntity head = group.head;
        BigDecimal charged = ZERO;
        LocalDate chargeDate = null;
        String invoice = null;
        String vendorShipment = null;
        String shipmentNumber = null;
        List<Long> chargeIds = new ArrayList<>();
        for (CustomerAccountEntryEntity charge : group.charges) {
            charged = charged.add(nz(charge.getAmount()));
            chargeIds.add(charge.getId());
            if (charge.getEntryDate() != null && (chargeDate == null || charge.getEntryDate().isBefore(chargeDate))) {
                chargeDate = charge.getEntryDate();
            }
            invoice = firstNonBlank(invoice, charge.getInvoiceNumber());
            vendorShipment = firstNonBlank(vendorShipment, charge.getVendorShipmentNumber());
            if (shipmentNumber == null && charge.getProductShipmentId() != null) {
                ProductShipmentEntity shipment = shipmentsById.get(charge.getProductShipmentId());
                shipmentNumber = shipment != null ? shipment.getShipmentNumber() : null;
            }
        }
        for (CustomerAccountEntryEntity adjustment : group.adjustments) {
            charged = charged.add(nz(adjustment.getAmount()));
        }
        ProductionOrderEntity order = head.getProductionOrderId() != null
                ? ordersById.get(head.getProductionOrderId())
                : null;
        String orderCode = order != null ? order.getCode() : head.getDocumentNumber();

        Amounts amounts = splitCredits(group.credits);
        BigDecimal balance = charged.subtract(amounts.payments).subtract(amounts.credits).setScale(2, RoundingMode.HALF_UP);
        String documentNumber = firstNonBlank(invoice, vendorShipment, shipmentNumber, orderCode,
                head.getDocumentNumber(), "CARGO " + head.getId());

        return baseRow(summary, ROW_DOCUMENT, kindOfHead(head, order))
                .chargeEntryId(head.getId())
                .chargeEntryIds(chargeIds)
                .chargeCount(chargeIds.size())
                .duplicateCharges(chargeIds.size() > 1)
                .documentNumber(documentNumber)
                .invoiceNumber(invoice)
                .orderCode(orderCode)
                .shipmentNumber(shipmentNumber)
                .vendorShipmentNumber(vendorShipment)
                .chargeDate(chargeDate)
                .lastPaymentDate(amounts.lastPaymentDate)
                .chargedAmount(charged.setScale(2, RoundingMode.HALF_UP))
                .paymentsApplied(amounts.payments)
                .creditsApplied(amounts.credits)
                .balanceDue(balance)
                .paymentCount(amounts.paymentCount)
                .creditCount(amounts.creditCount)
                .status(statusOf(balance, amounts))
                .build();
    }

    private CustomerAccountPortfolioRowResponse openingRow(
            CustomerAccountSummaryResponse summary, CustomerAccountEntryEntity opening, String kind) {
        BigDecimal amount = nz(opening.getAmount()).setScale(2, RoundingMode.HALF_UP);
        return baseRow(summary, ROW_OPENING_BALANCE, kind)
                .chargeEntryId(opening.getId())
                .chargeEntryIds(List.of(opening.getId()))
                .chargeCount(1)
                .documentNumber("SALDO INICIAL")
                .chargeDate(opening.getEntryDate())
                .chargedAmount(amount)
                .paymentsApplied(ZERO)
                .creditsApplied(ZERO)
                .balanceDue(amount)
                .status("OPEN")
                .build();
    }

    private CustomerAccountPortfolioRowResponse adjustmentRow(
            CustomerAccountSummaryResponse summary, CustomerAccountEntryEntity adjustment, String kind) {
        BigDecimal amount = nz(adjustment.getAmount()).setScale(2, RoundingMode.HALF_UP);
        return baseRow(summary, ROW_DOCUMENT, kind)
                .chargeEntryId(adjustment.getId())
                .chargeEntryIds(List.of(adjustment.getId()))
                .chargeCount(1)
                .documentNumber("AJUSTE ENVIO " + adjustment.getId())
                .chargeDate(adjustment.getEntryDate())
                .chargedAmount(amount)
                .paymentsApplied(ZERO)
                .creditsApplied(ZERO)
                .balanceDue(amount)
                .status("OPEN")
                .build();
    }

    private CustomerAccountPortfolioRowResponse orphanCreditRow(
            CustomerAccountSummaryResponse summary, List<CustomerAccountEntryEntity> credits, String kind) {
        Amounts amounts = splitCredits(credits);
        BigDecimal balance = ZERO.subtract(amounts.payments).subtract(amounts.credits).setScale(2, RoundingMode.HALF_UP);
        return baseRow(summary, ROW_ORPHAN_CREDIT, kind)
                .chargeEntryIds(List.of())
                .documentNumber("ABONOS A CARGO ANULADO")
                .lastPaymentDate(amounts.lastPaymentDate)
                .chargedAmount(ZERO)
                .paymentsApplied(amounts.payments)
                .creditsApplied(amounts.credits)
                .balanceDue(balance)
                .paymentCount(amounts.paymentCount)
                .creditCount(amounts.creditCount)
                .status("OPEN")
                .build();
    }

    private CustomerAccountPortfolioRowResponse.CustomerAccountPortfolioRowResponseBuilder baseRow(
            CustomerAccountSummaryResponse summary, String rowType, String kind) {
        return CustomerAccountPortfolioRowResponse.builder()
                .customerId(summary.getCustomerId())
                .customerName(summary.getCustomerName())
                .legacyCode(summary.getLegacyCode())
                .nit(summary.getNit())
                .routeLocationCode(summary.getRouteLocationCode())
                .routeLocationLabel(summary.getRouteLocationLabel())
                .rowType(rowType)
                .orderKind(kind);
    }

    // ---- créditos / pagos ----------------------------------------------------------------

    private Amounts splitCredits(List<CustomerAccountEntryEntity> credits) {
        Amounts amounts = new Amounts();
        for (CustomerAccountEntryEntity entry : credits) {
            BigDecimal applied = accountService.resolveEntryAppliedCredit(entry);
            if (TYPE_PAYMENT.equalsIgnoreCase(entry.getEntryType())) {
                BigDecimal cash = nz(entry.getAmount()).min(applied);
                amounts.payments = amounts.payments.add(cash);
                amounts.credits = amounts.credits.add(applied.subtract(cash));
                amounts.paymentCount++;
                LocalDate paidOn = entry.getCollectionDate() != null ? entry.getCollectionDate() : entry.getEntryDate();
                if (paidOn != null && (amounts.lastPaymentDate == null || paidOn.isAfter(amounts.lastPaymentDate))) {
                    amounts.lastPaymentDate = paidOn;
                }
            } else {
                amounts.credits = amounts.credits.add(applied);
                amounts.creditCount++;
            }
        }
        amounts.payments = amounts.payments.setScale(2, RoundingMode.HALF_UP);
        amounts.credits = amounts.credits.setScale(2, RoundingMode.HALF_UP);
        return amounts;
    }

    private static final class Amounts {
        private BigDecimal payments = ZERO;
        private BigDecimal credits = ZERO;
        private int paymentCount;
        private int creditCount;
        private LocalDate lastPaymentDate;
    }

    /** Un documento sigue en cartera mientras su saldo no sea cero (un saldo negativo es crédito a favor). */
    private static boolean hasBalance(CustomerAccountPortfolioRowResponse row) {
        return row.getBalanceDue().abs().compareTo(BALANCE_EPSILON) >= 0;
    }

    private static String statusOf(BigDecimal balance, Amounts amounts) {
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return "PAID";
        }
        return amounts.payments.add(amounts.credits).compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "OPEN";
    }

    // ---- totales -------------------------------------------------------------------------

    private CustomerAccountPortfolioCustomerResponse totalsFor(
            CustomerAccountSummaryResponse summary, List<CustomerAccountPortfolioRowResponse> rows) {
        BigDecimal charged = ZERO;
        BigDecimal payments = ZERO;
        BigDecimal credits = ZERO;
        BigDecimal net = ZERO;
        for (CustomerAccountPortfolioRowResponse row : rows) {
            charged = charged.add(row.getChargedAmount());
            payments = payments.add(row.getPaymentsApplied());
            credits = credits.add(row.getCreditsApplied());
            net = net.add(row.getBalanceDue());
        }
        net = net.setScale(2, RoundingMode.HALF_UP);
        boolean positive = net.compareTo(BigDecimal.ZERO) > 0;
        return CustomerAccountPortfolioCustomerResponse.builder()
                .customerId(summary.getCustomerId())
                .customerName(summary.getCustomerName())
                .legacyCode(summary.getLegacyCode())
                .routeLocationCode(summary.getRouteLocationCode())
                .documentCount((int) rows.stream().filter(r -> !ROW_ORPHAN_CREDIT.equals(r.getRowType())).count())
                .chargedAmount(charged.setScale(2, RoundingMode.HALF_UP))
                .paymentsApplied(payments.setScale(2, RoundingMode.HALF_UP))
                .creditsApplied(credits.setScale(2, RoundingMode.HALF_UP))
                .netBalance(net)
                .balanceDue(positive ? net : ZERO)
                .creditBalance(net.compareTo(BigDecimal.ZERO) < 0 ? net.abs() : ZERO)
                .build();
    }

    private static BigDecimal sum(
            List<CustomerAccountPortfolioCustomerResponse> customers,
            java.util.function.Function<CustomerAccountPortfolioCustomerResponse, BigDecimal> getter) {
        return customers.stream().map(getter).reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    // ---- anexo de movimientos --------------------------------------------------------------

    private List<CustomerAccountPortfolioMovementResponse> buildMovements(
            CustomerAccountSummaryResponse summary,
            List<CustomerAccountEntryEntity> entries,
            String kind,
            Map<Long, ProductionOrderEntity> ordersById,
            LocalDate from,
            LocalDate to) {
        List<CustomerAccountPortfolioMovementResponse> movements = new ArrayList<>();
        for (CustomerAccountEntryEntity entry : entries) {
            if (!kind.equals(kindOf(entry, ordersById))) {
                continue;
            }
            LocalDate date = entry.getEntryDate();
            if (date == null || (from != null && date.isBefore(from)) || (to != null && date.isAfter(to))) {
                continue;
            }
            boolean active = STATUS_ACTIVE.equalsIgnoreCase(entry.getStatus());
            BigDecimal debit = ZERO;
            BigDecimal credit = ZERO;
            if (active && isDebit(entry.getEntryType())) {
                debit = nz(entry.getAmount()).setScale(2, RoundingMode.HALF_UP);
            } else if (active && isCredit(entry.getEntryType())) {
                credit = accountService.resolveEntryAppliedCredit(entry);
            }
            movements.add(CustomerAccountPortfolioMovementResponse.builder()
                    .entryId(entry.getId())
                    .customerId(summary.getCustomerId())
                    .customerName(summary.getCustomerName())
                    .legacyCode(summary.getLegacyCode())
                    .entryDate(date)
                    .collectionDate(entry.getCollectionDate())
                    .entryType(entry.getEntryType())
                    .documentNumber(firstNonBlank(entry.getInvoiceNumber(), entry.getDocumentNumber(),
                            entry.getVendorShipmentNumber()))
                    .reference(firstNonBlank(entry.getReference(), entry.getReceiptNumber()))
                    .description(entry.getDescription())
                    .amount(nz(entry.getAmount()).setScale(2, RoundingMode.HALF_UP))
                    .debit(debit)
                    .credit(credit)
                    .status(entry.getStatus())
                    .voidReason(active ? null : entry.getVoidReason())
                    .appliedToEntryId(entry.getAppliedToEntryId())
                    .build());
        }
        return movements;
    }

    // ---- utilidades ------------------------------------------------------------------------

    private Map<Long, ProductionOrderEntity> loadOrders(Map<Long, List<CustomerAccountEntryEntity>> entriesByCustomer) {
        Set<Long> ids = entriesByCustomer.values().stream()
                .flatMap(List::stream)
                .map(CustomerAccountEntryEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductionOrderEntity> map = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (ProductionOrderEntity order : productionOrderRepository.findAllById(ids)) {
                map.put(order.getId(), order);
            }
        }
        return map;
    }

    private Map<Long, ProductShipmentEntity> loadShipments(Map<Long, List<CustomerAccountEntryEntity>> entriesByCustomer) {
        Set<Long> ids = entriesByCustomer.values().stream()
                .flatMap(List::stream)
                .map(CustomerAccountEntryEntity::getProductShipmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductShipmentEntity> map = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ProductShipmentEntity shipment : productShipmentRepository.findAllById(ids)) {
                map.put(shipment.getId(), shipment);
            }
        }
        return map;
    }

    /** Misma regla que el saldo por cartera del listado: orden viva si existe, si no el tipo guardado. */
    private String kindOf(CustomerAccountEntryEntity entry, Map<Long, ProductionOrderEntity> ordersById) {
        String kind;
        if (entry.getProductionOrderId() != null && ordersById.containsKey(entry.getProductionOrderId())) {
            kind = accountService.resolveReceivableOrderKind(ordersById.get(entry.getProductionOrderId()));
        } else {
            kind = entry.getOrderKind();
        }
        return kind != null ? kind.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String kindOfHead(CustomerAccountEntryEntity head, ProductionOrderEntity order) {
        String kind = order != null ? accountService.resolveReceivableOrderKind(order) : head.getOrderKind();
        return kind != null ? kind.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static int chargeLevel(CustomerAccountEntryEntity charge) {
        if (charge.getProductShipmentId() != null) {
            return 2;
        }
        return charge.getPartialReleaseId() != null ? 1 : 0;
    }

    /** {@code head} ya representa el documento de {@code charge} (mismo nivel exacto o nivel superior). */
    private static boolean covers(CustomerAccountEntryEntity head, CustomerAccountEntryEntity charge) {
        if (!Objects.equals(head.getProductionOrderId(), charge.getProductionOrderId())) {
            return false;
        }
        if (head.getProductShipmentId() == null) {
            return head.getPartialReleaseId() == null
                    || Objects.equals(head.getPartialReleaseId(), charge.getPartialReleaseId());
        }
        return Objects.equals(head.getProductShipmentId(), charge.getProductShipmentId())
                && Objects.equals(head.getPartialReleaseId(), charge.getPartialReleaseId());
    }

    private static final class ChargeGroup {
        private final CustomerAccountEntryEntity head;
        private final List<CustomerAccountEntryEntity> charges = new ArrayList<>();
        private final List<CustomerAccountEntryEntity> credits = new ArrayList<>();
        private final List<CustomerAccountEntryEntity> adjustments = new ArrayList<>();

        private ChargeGroup(CustomerAccountEntryEntity head) {
            this.head = head;
            this.charges.add(head);
        }
    }

    private static boolean customerMatchesSearch(CustomerAccountSummaryResponse customer, String searchNorm) {
        return contains(customer.getCustomerName(), searchNorm)
                || contains(customer.getLegacyCode(), searchNorm)
                || contains(customer.getNit(), searchNorm)
                || contains(customer.getPhone(), searchNorm);
    }

    private static boolean rowMatchesSearch(CustomerAccountPortfolioRowResponse row, String searchNorm) {
        return contains(row.getDocumentNumber(), searchNorm)
                || contains(row.getInvoiceNumber(), searchNorm)
                || contains(row.getOrderCode(), searchNorm)
                || contains(row.getShipmentNumber(), searchNorm)
                || contains(row.getVendorShipmentNumber(), searchNorm);
    }

    private static boolean contains(String value, String searchNorm) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(searchNorm);
    }

    private static String normalizeKind(String raw) throws BusinessException {
        String kind = raw == null || raw.isBlank() ? "OPV" : raw.trim().toUpperCase(Locale.ROOT);
        if (!"OPV".equals(kind) && !"OPC".equals(kind)) {
            throw new BusinessException("La cartera debe ser OPV (Fossiles) u OPC (GCF).");
        }
        return kind;
    }

    private static boolean isDebit(String type) {
        return TYPE_CHARGE.equalsIgnoreCase(type)
                || TYPE_OPENING_BALANCE.equalsIgnoreCase(type)
                || TYPE_CHARGE_ADJUSTMENT.equalsIgnoreCase(type);
    }

    private static boolean isCredit(String type) {
        return TYPE_PAYMENT.equalsIgnoreCase(type)
                || TYPE_CREDIT_NOTE.equalsIgnoreCase(type)
                || TYPE_RETURN.equalsIgnoreCase(type);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
