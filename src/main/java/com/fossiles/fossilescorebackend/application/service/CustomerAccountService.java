package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.response.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.DeliveryRouteCatalog;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerAccountService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_VOID = "VOID";
    private static final String TYPE_CHARGE = "CHARGE";
    private static final String TYPE_PAYMENT = "PAYMENT";
    private static final String TYPE_CREDIT_NOTE = "CREDIT_NOTE";
    private static final String TYPE_OPENING_BALANCE = "OPENING_BALANCE";
    private static final String TYPE_RETURN = "RETURN";
    private static final String CONCEPT_DISCHARGE = "11";
    private static final String OPV_PACKING_TAG = "__OPV_PACKING__:";
    private static final String OPV_SHIPPING_TAG = "__OPV_SHIPPING__:";

    private final CustomerAccountEntryRepository entryRepository;
    private final CustomerRepository customerRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderPartialReleaseRepository partialReleaseRepository;
    private final ProductShipmentRepository productShipmentRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<CustomerAccountSummaryResponse> getSummary(
            String search,
            boolean luisFelipeOnly,
            boolean positiveBalanceOnly,
            String regionCode,
            Integer routeNumber,
            String routeLocationCode) {
        Set<Long> lfCustomerIds = new HashSet<>(entryRepository.findLuisFelipeReceivableCustomerIds());
        List<CustomerEntity> customers = customerRepository.findAll();

        String searchNorm = search != null ? search.trim().toLowerCase(Locale.ROOT) : "";
        String regionFilter = regionCode != null && !regionCode.isBlank()
                ? regionCode.trim().toUpperCase(Locale.ROOT)
                : null;
        String routeCodeFilter = routeLocationCode != null && !routeLocationCode.isBlank()
                ? routeLocationCode.trim().toUpperCase(Locale.ROOT)
                : null;

        Map<Long, List<CustomerAccountEntryEntity>> entriesByCustomer = entryRepository.findAll().stream()
                .filter(e -> STATUS_ACTIVE.equalsIgnoreCase(e.getStatus()))
                .collect(Collectors.groupingBy(CustomerAccountEntryEntity::getCustomerId));

        Map<Long, Long> lfOrderCountByCustomer = productionOrderRepository.findAll().stream()
                .filter(this::isLfReceivableOrder)
                .filter(o -> o.getCustomerId() != null)
                .collect(Collectors.groupingBy(ProductionOrderEntity::getCustomerId, Collectors.counting()));

        List<CustomerAccountSummaryResponse> rows = new ArrayList<>();

        for (CustomerEntity customer : customers) {
            if (luisFelipeOnly && !lfCustomerIds.contains(customer.getId())) {
                continue;
            }
            if (!searchNorm.isEmpty() && !matchesSearch(customer, searchNorm)) {
                continue;
            }

            RouteMeta routeMeta = resolveRouteMeta(customer.getRouteLocationCode());
            if (regionFilter != null) {
                if (routeMeta.regionCode() == null || !regionFilter.equals(routeMeta.regionCode())) {
                    continue;
                }
            }
            if (routeNumber != null) {
                if (routeMeta.routeNumber() == null || !routeNumber.equals(routeMeta.routeNumber())) {
                    continue;
                }
            }
            if (routeCodeFilter != null) {
                String customerCode = customer.getRouteLocationCode() != null
                        ? customer.getRouteLocationCode().trim().toUpperCase(Locale.ROOT)
                        : null;
                if (!routeCodeFilter.equals(customerCode)) {
                    continue;
                }
            }

            List<CustomerAccountEntryEntity> entries = entriesByCustomer.getOrDefault(customer.getId(), List.of());
            BigDecimal balance = computeBalance(entries);
            if (positiveBalanceOnly && balance.compareTo(BigDecimal.ZERO) <= 0 && entries.isEmpty()) {
                continue;
            }
            if (positiveBalanceOnly && balance.compareTo(BigDecimal.ZERO) <= 0 && !lfCustomerIds.contains(customer.getId())) {
                continue;
            }

            LocalDate lastCharge = entries.stream()
                    .filter(e -> isDebitType(e.getEntryType()))
                    .map(CustomerAccountEntryEntity::getEntryDate)
                    .max(LocalDate::compareTo)
                    .orElse(null);
            LocalDate lastPayment = entries.stream()
                    .filter(e -> isCreditType(e.getEntryType()))
                    .map(e -> e.getCollectionDate() != null ? e.getCollectionDate() : e.getEntryDate())
                    .max(LocalDate::compareTo)
                    .orElse(null);

            AccountSplit split = splitBalance(balance);
            KindSplit kindSplit = computeKindSplit(entries);
            rows.add(CustomerAccountSummaryResponse.builder()
                    .customerId(customer.getId())
                    .customerName(customer.getName())
                    .legacyCode(customer.getLegacyCode())
                    .nit(customer.getNit())
                    .phone(customer.getPhone())
                    .balance(balance)
                    .balanceDue(split.due())
                    .creditBalance(split.credit())
                    .balanceDueOpv(kindSplit.opvDue())
                    .balanceDueOpc(kindSplit.opcDue())
                    .lastChargeDate(lastCharge)
                    .lastPaymentDate(lastPayment)
                    .lfOrderCount(lfOrderCountByCustomer.getOrDefault(customer.getId(), 0L).intValue())
                    .routeLocationCode(customer.getRouteLocationCode())
                    .routeRegionCode(routeMeta.regionCode())
                    .routeNumber(routeMeta.routeNumber())
                    .routeLocationLabel(routeMeta.label())
                    .build());
        }

        rows.sort(Comparator
                .comparing((CustomerAccountSummaryResponse r) -> DeliveryRouteCatalog.regionSortOrder(r.getRouteRegionCode()))
                .thenComparing(r -> r.getRouteNumber() == null ? 999 : r.getRouteNumber())
                .thenComparing(r -> r.getRouteLocationCode() == null ? "ZZZZ" : r.getRouteLocationCode())
                .thenComparing(CustomerAccountSummaryResponse::getBalanceDue, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> Optional.ofNullable(r.getCustomerName()).orElse(""), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    @Transactional(readOnly = true)
    public List<CustomerAccountSummaryResponse> getSummary(String search, boolean luisFelipeOnly, boolean positiveBalanceOnly) {
        return getSummary(search, luisFelipeOnly, positiveBalanceOnly, null, null, null);
    }

    @Transactional(readOnly = true)
    public CustomerAccountPrintReportResponse getPrintReport(
            String search,
            boolean luisFelipeOnly,
            boolean positiveBalanceOnly,
            LocalDate from,
            LocalDate to,
            String regionCode,
            Integer routeNumber,
            String routeLocationCode) throws ResourceNotFoundException {
        List<CustomerAccountSummaryResponse> summaries = getSummary(
                search, luisFelipeOnly, positiveBalanceOnly, regionCode, routeNumber, routeLocationCode);
        List<CustomerAccountPrintCustomerSection> sections = new ArrayList<>();
        BigDecimal totalDue = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (CustomerAccountSummaryResponse summary : summaries) {
            CustomerEntity customer = loadCustomer(summary.getCustomerId());
            CustomerAccountStatementResponse statement = getStatement(summary.getCustomerId(), from, to);
            BigDecimal due = summary.getBalanceDue() != null ? summary.getBalanceDue() : splitBalance(summary.getBalance()).due();
            BigDecimal credit = summary.getCreditBalance() != null
                    ? summary.getCreditBalance()
                    : splitBalance(summary.getBalance()).credit();
            totalDue = totalDue.add(due);
            totalCredit = totalCredit.add(credit);

            sections.add(CustomerAccountPrintCustomerSection.builder()
                    .customerId(summary.getCustomerId())
                    .customerName(summary.getCustomerName())
                    .nit(summary.getNit())
                    .phone(summary.getPhone())
                    .email(customer.getEmail())
                    .address(customer.getAddress())
                    .balance(summary.getBalance())
                    .balanceDue(due)
                    .creditBalance(credit)
                    .lastChargeDate(summary.getLastChargeDate())
                    .lastPaymentDate(summary.getLastPaymentDate())
                    .lfOrderCount(summary.getLfOrderCount())
                    .routeLocationCode(summary.getRouteLocationCode())
                    .routeRegionCode(summary.getRouteRegionCode())
                    .routeNumber(summary.getRouteNumber())
                    .routeLocationLabel(summary.getRouteLocationLabel())
                    .lines(statement.getLines())
                    .build());
        }

        return CustomerAccountPrintReportResponse.builder()
                .generatedAt(LocalDateTime.now())
                .fromDate(from)
                .toDate(to)
                .totalBalanceDue(totalDue.setScale(2, RoundingMode.HALF_UP))
                .totalCreditBalance(totalCredit.setScale(2, RoundingMode.HALF_UP))
                .customerCount(sections.size())
                .customers(sections)
                .build();
    }

    @Transactional(readOnly = true)
    public CustomerAccountBalanceResponse getBalance(Long customerId) throws ResourceNotFoundException {
        loadCustomer(customerId);
        List<CustomerAccountEntryEntity> entries = loadActiveEntries(customerId);
        BigDecimal balance = computeBalance(entries);
        AccountSplit split = splitBalance(balance);
        KindSplit kindSplit = computeKindSplit(entries);
        return CustomerAccountBalanceResponse.builder()
                .customerId(customerId)
                .balance(balance)
                .balanceDue(split.due())
                .creditBalance(split.credit())
                .balanceDueOpv(kindSplit.opvDue())
                .balanceDueOpc(kindSplit.opcDue())
                .build();
    }

    @Transactional(readOnly = true)
    public CustomerAccountStatementResponse getStatement(Long customerId, LocalDate from, LocalDate to)
            throws ResourceNotFoundException {
        CustomerEntity customer = loadCustomer(customerId);
        List<CustomerAccountEntryEntity> allEntries = entryRepository.findByCustomerIdOrderByEntryDateAscIdAsc(customerId);
        List<CustomerAccountEntryEntity> allActive = allEntries.stream()
                .filter(e -> STATUS_ACTIVE.equalsIgnoreCase(e.getStatus()))
                .toList();

        BigDecimal openingBalance = BigDecimal.ZERO;
        if (from != null) {
            openingBalance = computeBalance(allActive.stream()
                    .filter(e -> e.getEntryDate().isBefore(from))
                    .toList());
        }

        List<CustomerAccountEntryEntity> inRange = allEntries.stream()
                .filter(e -> from == null || !e.getEntryDate().isBefore(from))
                .filter(e -> to == null || !e.getEntryDate().isAfter(to))
                .toList();

        BigDecimal running = openingBalance;
        BigDecimal totalCharges = BigDecimal.ZERO;
        BigDecimal totalPayments = BigDecimal.ZERO;
        BigDecimal totalCreditNotes = BigDecimal.ZERO;
        BigDecimal totalReturns = BigDecimal.ZERO;
        List<CustomerAccountStatementLineResponse> lines = new ArrayList<>();

        for (CustomerAccountEntryEntity entry : inRange) {
            boolean active = STATUS_ACTIVE.equalsIgnoreCase(entry.getStatus());
            BigDecimal debit = BigDecimal.ZERO;
            BigDecimal credit = BigDecimal.ZERO;
            if (active) {
                if (isDebitType(entry.getEntryType())) {
                    debit = entry.getAmount();
                    running = running.add(debit);
                    totalCharges = totalCharges.add(debit);
                } else if (isCreditType(entry.getEntryType())) {
                    credit = entry.getAmount();
                    running = running.subtract(credit);
                    if (TYPE_PAYMENT.equalsIgnoreCase(entry.getEntryType())) {
                        totalPayments = totalPayments.add(credit);
                    } else if (TYPE_CREDIT_NOTE.equalsIgnoreCase(entry.getEntryType())) {
                        totalCreditNotes = totalCreditNotes.add(credit);
                    } else if (TYPE_RETURN.equalsIgnoreCase(entry.getEntryType())) {
                        totalReturns = totalReturns.add(credit);
                    }
                }
            }

            String orderCode = resolveOrderCode(entry.getProductionOrderId());
            lines.add(CustomerAccountStatementLineResponse.builder()
                    .id(entry.getId())
                    .entryDate(entry.getEntryDate())
                    .collectionDate(entry.getCollectionDate())
                    .entryType(entry.getEntryType())
                    .movementConceptCode(entry.getMovementConceptCode())
                    .reference(entry.getReference())
                    .receiptNumber(entry.getReceiptNumber())
                    .description(entry.getDescription())
                    .paymentMethod(entry.getPaymentMethod())
                    .invoiceNumber(entry.getInvoiceNumber())
                    .documentNumber(entry.getDocumentNumber())
                    .returnVoucherNumber(entry.getReturnVoucherNumber())
                    .productionOrderCode(orderCode)
                    .vendorShipmentNumber(entry.getVendorShipmentNumber())
                    .orderKind(entry.getOrderKind())
                    .grossCollectedAmount(entry.getGrossCollectedAmount())
                    .paymentDiscountAmount(entry.getPaymentDiscountAmount())
                    .status(entry.getStatus())
                    .debit(debit)
                    .credit(credit)
                    .runningBalance(active ? running.setScale(2, RoundingMode.HALF_UP) : null)
                    .build());
        }

        BigDecimal closingBalance = openingBalance
                .add(totalCharges)
                .subtract(totalPayments)
                .subtract(totalCreditNotes)
                .subtract(totalReturns)
                .setScale(2, RoundingMode.HALF_UP);
        AccountSplit closingSplit = splitBalance(closingBalance);
        KindSplit kindSplit = computeKindSplit(allActive);

        return CustomerAccountStatementResponse.builder()
                .customerId(customer.getId())
                .customerName(customer.getName())
                .legacyCode(customer.getLegacyCode())
                .nit(customer.getNit())
                .phone(customer.getPhone())
                .email(customer.getEmail())
                .address(customer.getAddress())
                .fromDate(from)
                .toDate(to)
                .openingBalance(openingBalance.setScale(2, RoundingMode.HALF_UP))
                .closingBalance(closingBalance)
                .closingBalanceDue(closingSplit.due())
                .closingCreditBalance(closingSplit.credit())
                .closingBalanceDueOpv(kindSplit.opvDue())
                .closingBalanceDueOpc(kindSplit.opcDue())
                .totalCharges(totalCharges.setScale(2, RoundingMode.HALF_UP))
                .totalPayments(totalPayments.setScale(2, RoundingMode.HALF_UP))
                .totalCreditNotes(totalCreditNotes.setScale(2, RoundingMode.HALF_UP))
                .totalReturns(totalReturns.setScale(2, RoundingMode.HALF_UP))
                .lines(lines)
                .build();
    }

    public CustomerAccountEntryResponse createEntry(Long customerId, CustomerAccountEntryRequest request)
            throws ResourceNotFoundException, BusinessException {
        CustomerEntity customer = loadCustomer(customerId);
        String entryType = normalizeEntryType(request.getEntryType());
        String conceptCode = trimToNull(request.getMovementConceptCode());

        ProductionOrderEntity linkedOrder = resolveLinkedOrder(customer, request);
        String orderKind = linkedOrder != null ? classifyOrderKind(linkedOrder) : null;

        validateDocumentLinks(entryType, request, linkedOrder);
        preventDuplicateCharge(customer.getId(), entryType, request);

        BigDecimal netAmount = resolveNetAmount(entryType, request);
        CustomerAccountEntryEntity chargeTarget = resolveAppliedCharge(customer.getId(), entryType, conceptCode, request);

        if (chargeTarget != null) {
            BigDecimal balanceDue = computeChargeBalanceDue(chargeTarget);
            if (netAmount.compareTo(balanceDue) > 0) {
                throw new BusinessException("El monto excede el saldo pendiente del documento (Q "
                        + balanceDue.setScale(2, RoundingMode.HALF_UP) + ").");
            }
        }

        validatePaymentFields(entryType, conceptCode, request, chargeTarget);

        Long userId = securityUtil.getCurrentUserId();
        String vendorShipmentNumber = resolveVendorShipmentNumber(request, linkedOrder, chargeTarget);
        String invoiceNumber = firstNonBlank(request.getInvoiceNumber(), vendorShipmentNumber, chargeTarget != null ? chargeTarget.getInvoiceNumber() : null);
        String documentNumber = firstNonBlank(
                request.getDocumentNumber(),
                linkedOrder != null ? linkedOrder.getCode() : null,
                chargeTarget != null ? chargeTarget.getDocumentNumber() : null);

        CustomerAccountEntryEntity saved = entryRepository.save(CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType(entryType)
                .entryDate(request.getEntryDate())
                .amount(netAmount)
                .reference(firstNonBlank(request.getReference(), request.getReceiptNumber()))
                .description(trimToNull(request.getDescription()))
                .paymentMethod(resolvePaymentMethod(entryType, conceptCode, request))
                .movementConceptCode(conceptCode)
                .receiptNumber(trimToNull(request.getReceiptNumber()))
                .collectionDate(request.getCollectionDate())
                .paymentDiscountAmount(scaleOrNull(request.getPaymentDiscountAmount()))
                .paymentDiscountPercent(scalePercentOrNull(request.getPaymentDiscountPercent()))
                .grossCollectedAmount(scaleOrNull(request.getGrossCollectedAmount()))
                .appliedToEntryId(chargeTarget != null ? chargeTarget.getId() : request.getAppliedToEntryId())
                .invoiceNumber(trimToNull(invoiceNumber))
                .documentNumber(trimToNull(documentNumber))
                .returnVoucherNumber(trimToNull(request.getReturnVoucherNumber()))
                .returnDate(request.getReturnDate())
                .returnReason(trimToNull(request.getReturnReason()))
                .productionOrderId(firstNonNull(request.getProductionOrderId(), chargeTarget != null ? chargeTarget.getProductionOrderId() : null))
                .partialReleaseId(firstNonNull(request.getPartialReleaseId(), chargeTarget != null ? chargeTarget.getPartialReleaseId() : null))
                .productShipmentId(firstNonNull(request.getProductShipmentId(), chargeTarget != null ? chargeTarget.getProductShipmentId() : null))
                .vendorShipmentNumber(trimToNull(vendorShipmentNumber))
                .orderKind(orderKind != null ? orderKind : chargeTarget != null ? chargeTarget.getOrderKind() : null)
                .status(STATUS_ACTIVE)
                .createdBy(userId)
                .updatedBy(userId)
                .build());

        return toEntryResponse(saved);
    }

    public CustomerAccountEntryResponse voidEntry(Long entryId, CustomerAccountEntryVoidRequest request)
            throws ResourceNotFoundException, BusinessException {
        CustomerAccountEntryEntity entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerAccountEntry", entryId));
        if (STATUS_VOID.equalsIgnoreCase(entry.getStatus())) {
            throw new BusinessException("El movimiento ya está anulado.");
        }
        entry.setStatus(STATUS_VOID);
        entry.setVoidedAt(LocalDateTime.now());
        entry.setVoidedBy(securityUtil.getCurrentUserId());
        entry.setVoidReason(request.getVoidReason().trim());
        entry.setUpdatedBy(securityUtil.getCurrentUserId());
        return toEntryResponse(entryRepository.save(entry));
    }

    @Transactional(readOnly = true)
    public List<LfSalesDocumentResponse> getLfDocuments(Long customerId, boolean withBalance) throws ResourceNotFoundException {
        loadCustomer(customerId);
        List<CustomerAccountEntryEntity> entries = loadActiveEntries(customerId);
        Map<Long, ChargeMeta> chargeMetaById = buildChargeMetaMap(entries);

        return productionOrderRepository.findByCustomerId(customerId).stream()
                .filter(this::isLfReceivableOrder)
                .sorted(Comparator.comparing(ProductionOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(order -> toLfDocument(order, entries, chargeMetaById, withBalance))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LfReceivableDocumentResponse> getReceivableDocuments(Long customerId, String orderKindFilter)
            throws ResourceNotFoundException {
        loadCustomer(customerId);
        List<CustomerAccountEntryEntity> entries = loadActiveEntries(customerId);
        String kindFilter = orderKindFilter != null && !orderKindFilter.isBlank()
                ? orderKindFilter.trim().toUpperCase(Locale.ROOT)
                : null;

        return entries.stream()
                .filter(e -> TYPE_CHARGE.equalsIgnoreCase(e.getEntryType()))
                .map(charge -> toReceivableDocument(charge, entries))
                .filter(doc -> doc.getBalanceDue() != null && doc.getBalanceDue().compareTo(BigDecimal.ZERO) > 0)
                .filter(doc -> kindFilter == null || kindFilter.equals(doc.getOrderKind()))
                .sorted(Comparator.comparing(LfReceivableDocumentResponse::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LfReceivableDocumentResponse::getInvoiceNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    private LfReceivableDocumentResponse toReceivableDocument(
            CustomerAccountEntryEntity charge,
            List<CustomerAccountEntryEntity> entries) {
        BigDecimal balanceDue = computeChargeBalanceDue(charge, entries);
        String orderCode = resolveOrderCode(charge.getProductionOrderId());
        String partialLabel = resolvePartialReleaseLabel(charge.getPartialReleaseId());
        return LfReceivableDocumentResponse.builder()
                .chargeEntryId(charge.getId())
                .productionOrderId(charge.getProductionOrderId())
                .partialReleaseId(charge.getPartialReleaseId())
                .productShipmentId(charge.getProductShipmentId())
                .orderCode(orderCode)
                .orderKind(charge.getOrderKind())
                .invoiceNumber(firstNonBlank(charge.getInvoiceNumber(), charge.getVendorShipmentNumber()))
                .documentNumber(firstNonBlank(charge.getDocumentNumber(), orderCode, charge.getVendorShipmentNumber()))
                .partialReleaseLabel(partialLabel)
                .dueDate(charge.getEntryDate())
                .chargeAmount(charge.getAmount())
                .balanceDue(balanceDue)
                .chargeStatus(balanceDue.compareTo(BigDecimal.ZERO) <= 0 ? "PAID"
                        : balanceDue.compareTo(charge.getAmount()) < 0 ? "PARTIAL" : "OPEN")
                .build();
    }

    private LfSalesDocumentResponse toLfDocument(
            ProductionOrderEntity order,
            List<CustomerAccountEntryEntity> entries,
            Map<Long, ChargeMeta> chargeMetaById,
            boolean withBalance) {
        BigDecimal estimatedTotal = estimateLfOrderTotal(order);
        Optional<CustomerAccountEntryEntity> orderCharge = findActiveCharge(entries, order.getId(), null, null);
        BigDecimal chargedAmount = orderCharge.map(CustomerAccountEntryEntity::getAmount).orElse(BigDecimal.ZERO);
        BigDecimal balanceDue = orderCharge.map(c -> computeChargeBalanceDue(c, entries)).orElse(BigDecimal.ZERO);

        List<ProductionOrderPartialReleaseEntity> releases =
                partialReleaseRepository.findByProductionOrderIdOrderBySequenceNumAsc(order.getId());
        List<LfPartialReleaseDocumentResponse> partialDocs = releases.stream()
                .map(release -> toPartialReleaseDoc(release, order, entries, chargeMetaById, withBalance))
                .collect(Collectors.toList());

        return LfSalesDocumentResponse.builder()
                .productionOrderId(order.getId())
                .orderCode(order.getCode())
                .orderKind(classifyOrderKind(order))
                .orderType(order.getOrderType())
                .vendorShipmentNumber(order.getVendorShipmentNumber())
                .status(order.getStatus())
                .startDate(order.getStartDate())
                .deliveryDate(order.getDeliveryDate())
                .estimatedTotal(estimatedTotal)
                .chargedAmount(withBalance ? chargedAmount : null)
                .balanceDue(withBalance ? balanceDue : null)
                .chargeStatus(resolveChargeStatus(orderCharge, balanceDue))
                .chargeEntryId(orderCharge.map(CustomerAccountEntryEntity::getId).orElse(null))
                .vendorShipmentVoided(order.getVendorShipmentVoidedAt() != null)
                .partialReleases(partialDocs)
                .build();
    }

    private LfPartialReleaseDocumentResponse toPartialReleaseDoc(
            ProductionOrderPartialReleaseEntity release,
            ProductionOrderEntity order,
            List<CustomerAccountEntryEntity> entries,
            Map<Long, ChargeMeta> chargeMetaById,
            boolean withBalance) {
        Optional<CustomerAccountEntryEntity> releaseCharge =
                findActiveCharge(entries, order.getId(), release.getId(), null);
        BigDecimal chargedAmount = releaseCharge.map(CustomerAccountEntryEntity::getAmount).orElse(BigDecimal.ZERO);
        BigDecimal balanceDue = releaseCharge.map(c -> computeChargeBalanceDue(c, entries)).orElse(BigDecimal.ZERO);

        List<ProductShipmentEntity> shipments = productShipmentRepository.findByPartialReleaseId(release.getId());
        List<LfShipmentDocumentResponse> shipmentDocs = shipments.stream()
                .map(shipment -> toShipmentDoc(shipment, order, release.getId(), entries, withBalance))
                .collect(Collectors.toList());

        return LfPartialReleaseDocumentResponse.builder()
                .partialReleaseId(release.getId())
                .sequenceNum(release.getSequenceNum())
                .label(release.getLabel())
                .status(release.getStatus())
                .estimatedTotal(null)
                .chargedAmount(withBalance ? chargedAmount : null)
                .balanceDue(withBalance ? balanceDue : null)
                .chargeStatus(resolveChargeStatus(releaseCharge, balanceDue))
                .chargeEntryId(releaseCharge.map(CustomerAccountEntryEntity::getId).orElse(null))
                .shipments(shipmentDocs)
                .build();
    }

    private LfShipmentDocumentResponse toShipmentDoc(
            ProductShipmentEntity shipment,
            ProductionOrderEntity order,
            Long partialReleaseId,
            List<CustomerAccountEntryEntity> entries,
            boolean withBalance) {
        Optional<CustomerAccountEntryEntity> shipmentCharge =
                findActiveCharge(entries, order.getId(), partialReleaseId, shipment.getId());
        BigDecimal chargedAmount = shipmentCharge.map(CustomerAccountEntryEntity::getAmount).orElse(BigDecimal.ZERO);
        BigDecimal balanceDue = shipmentCharge.map(c -> computeChargeBalanceDue(c, entries)).orElse(BigDecimal.ZERO);

        return LfShipmentDocumentResponse.builder()
                .productShipmentId(shipment.getId())
                .shipmentNumber(shipment.getShipmentNumber())
                .status(shipment.getStatus())
                .estimatedTotal(null)
                .chargedAmount(withBalance ? chargedAmount : null)
                .balanceDue(withBalance ? balanceDue : null)
                .chargeStatus(resolveChargeStatus(shipmentCharge, balanceDue))
                .chargeEntryId(shipmentCharge.map(CustomerAccountEntryEntity::getId).orElse(null))
                .build();
    }

    private static String resolveChargeStatus(Optional<CustomerAccountEntryEntity> charge, BigDecimal balanceDue) {
        if (charge.isEmpty()) {
            return "NONE";
        }
        if (balanceDue == null || balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            return "PAID";
        }
        if (balanceDue.compareTo(charge.get().getAmount()) < 0) {
            return "PARTIAL";
        }
        return "CHARGED";
    }

    private Optional<CustomerAccountEntryEntity> findActiveCharge(
            List<CustomerAccountEntryEntity> entries,
            Long productionOrderId,
            Long partialReleaseId,
            Long productShipmentId) {
        return entries.stream()
                .filter(e -> TYPE_CHARGE.equalsIgnoreCase(e.getEntryType()))
                .filter(e -> Objects.equals(e.getProductionOrderId(), productionOrderId))
                .filter(e -> Objects.equals(e.getPartialReleaseId(), partialReleaseId))
                .filter(e -> Objects.equals(e.getProductShipmentId(), productShipmentId))
                .findFirst();
    }

    private BigDecimal computeChargeBalanceDue(CustomerAccountEntryEntity charge) {
        return computeChargeBalanceDue(charge, loadActiveEntries(charge.getCustomerId()));
    }

    private BigDecimal computeChargeBalanceDue(CustomerAccountEntryEntity charge, List<CustomerAccountEntryEntity> entries) {
        BigDecimal applied = entries.stream()
                .filter(e -> charge.getId().equals(e.getAppliedToEntryId()))
                .filter(e -> isCreditType(e.getEntryType()))
                .map(CustomerAccountEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return charge.getAmount().subtract(applied).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<Long, ChargeMeta> buildChargeMetaMap(List<CustomerAccountEntryEntity> entries) {
        Map<Long, ChargeMeta> map = new HashMap<>();
        for (CustomerAccountEntryEntity entry : entries) {
            if (TYPE_CHARGE.equalsIgnoreCase(entry.getEntryType())) {
                map.put(entry.getId(), new ChargeMeta(entry, computeChargeBalanceDue(entry, entries)));
            }
        }
        return map;
    }

    private record ChargeMeta(CustomerAccountEntryEntity charge, BigDecimal balanceDue) {}

    private void validateDocumentLinks(String entryType, CustomerAccountEntryRequest request, ProductionOrderEntity linkedOrder)
            throws BusinessException {
        if (request.getPartialReleaseId() != null) {
            ProductionOrderPartialReleaseEntity release = partialReleaseRepository.findById(request.getPartialReleaseId())
                    .orElseThrow(() -> new BusinessException("Liberación parcial no encontrada."));
            if (linkedOrder != null && !release.getProductionOrderId().equals(linkedOrder.getId())) {
                throw new BusinessException("La liberación parcial no pertenece a la orden indicada.");
            }
        }
        if (request.getProductShipmentId() != null) {
            ProductShipmentEntity shipment = productShipmentRepository.findById(request.getProductShipmentId())
                    .orElseThrow(() -> new BusinessException("Envío no encontrado."));
            if (linkedOrder != null && shipment.getProductionOrderId() != null
                    && !shipment.getProductionOrderId().equals(linkedOrder.getId())) {
                throw new BusinessException("El envío no pertenece a la orden indicada.");
            }
        }
        if (TYPE_RETURN.equals(entryType) && trimToNull(request.getReturnVoucherNumber()) == null) {
            throw new BusinessException("La boleta de devolución es obligatoria.");
        }
    }

    private void preventDuplicateCharge(Long customerId, String entryType, CustomerAccountEntryRequest request)
            throws BusinessException {
        if (!TYPE_CHARGE.equals(entryType)) {
            return;
        }
        Optional<CustomerAccountEntryEntity> existing = entryRepository.findActiveCharge(
                customerId,
                request.getProductionOrderId(),
                request.getPartialReleaseId(),
                request.getProductShipmentId());
        if (existing.isPresent()) {
            throw new BusinessException("Ya existe un cargo activo para este documento.");
        }
    }

    private ProductionOrderEntity resolveLinkedOrder(CustomerEntity customer, CustomerAccountEntryRequest request)
            throws ResourceNotFoundException, BusinessException {
        if (request.getProductionOrderId() == null) {
            return null;
        }
        ProductionOrderEntity linkedOrder = productionOrderRepository.findById(request.getProductionOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", request.getProductionOrderId()));
        if (linkedOrder.getCustomerId() == null || !linkedOrder.getCustomerId().equals(customer.getId())) {
            throw new BusinessException("La orden de producción no pertenece a este cliente.");
        }
        if (!isLfReceivableOrder(linkedOrder)) {
            throw new BusinessException("Solo se pueden vincular órdenes OPV/OPC del vendedor Luis Felipe.");
        }
        return linkedOrder;
    }

    private CustomerAccountEntryEntity resolveAppliedCharge(
            Long customerId,
            String entryType,
            String conceptCode,
            CustomerAccountEntryRequest request) throws BusinessException {
        if (!TYPE_PAYMENT.equals(entryType) && !TYPE_RETURN.equals(entryType)) {
            return null;
        }
        Long appliedId = request.getAppliedToEntryId();
        if (appliedId == null) {
            if (CONCEPT_DISCHARGE.equals(conceptCode)) {
                throw new BusinessException("Debe seleccionar un documento con saldo pendiente (concepto 11).");
            }
            return null;
        }
        CustomerAccountEntryEntity charge = entryRepository.findById(appliedId)
                .orElseThrow(() -> new BusinessException("Cargo vinculado no encontrado."));
        if (!customerId.equals(charge.getCustomerId())) {
            throw new BusinessException("El cargo no pertenece a este cliente.");
        }
        if (!TYPE_CHARGE.equalsIgnoreCase(charge.getEntryType()) || !STATUS_ACTIVE.equalsIgnoreCase(charge.getStatus())) {
            throw new BusinessException("Solo se puede aplicar a un cargo activo.");
        }
        return charge;
    }

    private void validatePaymentFields(
            String entryType,
            String conceptCode,
            CustomerAccountEntryRequest request,
            CustomerAccountEntryEntity chargeTarget) throws BusinessException {
        if (!TYPE_PAYMENT.equals(entryType)) {
            return;
        }
        if (trimToNull(request.getReceiptNumber()) == null) {
            throw new BusinessException("El número de recibo es obligatorio.");
        }
        if (request.getCollectionDate() == null) {
            throw new BusinessException("La fecha de cobro (aplicación) es obligatoria.");
        }
        if (CONCEPT_DISCHARGE.equals(conceptCode) && chargeTarget == null) {
            throw new BusinessException("La descarga requiere un documento con saldo pendiente.");
        }
    }

    private BigDecimal resolveNetAmount(String entryType, CustomerAccountEntryRequest request) throws BusinessException {
        BigDecimal gross = request.getGrossCollectedAmount() != null
                ? request.getGrossCollectedAmount()
                : request.getAmount();
        if (gross == null || gross.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Ingrese un monto válido mayor a cero.");
        }
        gross = gross.setScale(2, RoundingMode.HALF_UP);

        BigDecimal discount = BigDecimal.ZERO;
        if (request.getPaymentDiscountAmount() != null && request.getPaymentDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            discount = request.getPaymentDiscountAmount().setScale(2, RoundingMode.HALF_UP);
        } else if (request.getPaymentDiscountPercent() != null && request.getPaymentDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            discount = gross.multiply(request.getPaymentDiscountPercent())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        if (TYPE_PAYMENT.equals(entryType) || TYPE_RETURN.equals(entryType)) {
            BigDecimal net = gross.subtract(discount);
            if (net.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("El monto neto debe ser mayor a cero.");
            }
            request.setGrossCollectedAmount(gross);
            request.setPaymentDiscountAmount(discount.compareTo(BigDecimal.ZERO) > 0 ? discount : null);
            return net.setScale(2, RoundingMode.HALF_UP);
        }
        return request.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    private String resolvePaymentMethod(String entryType, String conceptCode, CustomerAccountEntryRequest request) {
        if (!TYPE_PAYMENT.equals(entryType)) {
            return null;
        }
        if (trimToNull(request.getPaymentMethod()) != null) {
            return trimToNull(request.getPaymentMethod());
        }
        if ("3".equals(conceptCode)) {
            return "CHEQUE";
        }
        if ("4".equals(conceptCode)) {
            return "EFECTIVO";
        }
        return null;
    }

    private String resolveVendorShipmentNumber(
            CustomerAccountEntryRequest request,
            ProductionOrderEntity linkedOrder,
            CustomerAccountEntryEntity chargeTarget) {
        if (trimToNull(request.getVendorShipmentNumber()) != null) {
            return trimToNull(request.getVendorShipmentNumber());
        }
        if (chargeTarget != null && trimToNull(chargeTarget.getVendorShipmentNumber()) != null) {
            return chargeTarget.getVendorShipmentNumber();
        }
        if (linkedOrder != null) {
            return linkedOrder.getVendorShipmentNumber();
        }
        if (request.getProductShipmentId() != null) {
            return productShipmentRepository.findById(request.getProductShipmentId())
                    .map(ProductShipmentEntity::getShipmentNumber)
                    .orElse(null);
        }
        return null;
    }

    private String resolvePartialReleaseLabel(Long partialReleaseId) {
        if (partialReleaseId == null) {
            return null;
        }
        return partialReleaseRepository.findById(partialReleaseId)
                .map(r -> r.getLabel() != null ? r.getLabel() : "Parcial #" + r.getSequenceNum())
                .orElse(null);
    }

    private KindSplit computeKindSplit(List<CustomerAccountEntryEntity> entries) {
        BigDecimal opv = BigDecimal.ZERO;
        BigDecimal opc = BigDecimal.ZERO;
        for (CustomerAccountEntryEntity entry : entries) {
            if (!STATUS_ACTIVE.equalsIgnoreCase(entry.getStatus())) {
                continue;
            }
            String kind = entry.getOrderKind();
            if (kind == null) {
                continue;
            }
            BigDecimal delta = BigDecimal.ZERO;
            if (isDebitType(entry.getEntryType())) {
                delta = entry.getAmount();
            } else if (isCreditType(entry.getEntryType()) && entry.getAppliedToEntryId() != null) {
                delta = entry.getAmount().negate();
            } else if (isCreditType(entry.getEntryType()) && entry.getAppliedToEntryId() == null) {
                continue;
            }
            if ("OPV".equalsIgnoreCase(kind)) {
                opv = opv.add(delta);
            } else if ("OPC".equalsIgnoreCase(kind)) {
                opc = opc.add(delta);
            }
        }
        return new KindSplit(
                opv.compareTo(BigDecimal.ZERO) > 0 ? opv.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                opc.compareTo(BigDecimal.ZERO) > 0 ? opc.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
    }

    private record KindSplit(BigDecimal opvDue, BigDecimal opcDue) {}

    private BigDecimal estimateLfOrderTotal(ProductionOrderEntity order) {
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(order.getId());
        BigDecimal itemsTotal = BigDecimal.ZERO;
        for (ProductionOrderItemEntity item : items) {
            BigDecimal unitPrice = resolveUnitPrice(item);
            int qty = resolveItemQuantity(item);
            if (qty > 0) {
                itemsTotal = itemsTotal.add(unitPrice.multiply(BigDecimal.valueOf(qty)));
            }
        }
        OrderMeta meta = parseOrderMeta(order.getObservations());
        BigDecimal packingTotal = meta.packingItems.stream()
                .map(p -> p.unitPrice.multiply(p.quantity))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return itemsTotal.add(packingTotal).add(meta.shippingCost).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveUnitPrice(ProductionOrderItemEntity item) {
        if (item.getUnitPrice() != null && item.getUnitPrice().compareTo(BigDecimal.ZERO) >= 0) {
            return item.getUnitPrice();
        }
        if (item.getProductId() != null) {
            return productRepository.findById(item.getProductId())
                    .map(ProductEntity::getSellerPrice)
                    .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) >= 0)
                    .orElse(BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }

    private int resolveItemQuantity(ProductionOrderItemEntity item) {
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(item.getSizesData());
        if (!sizes.isEmpty()) {
            return sizes.values().stream()
                    .filter(Objects::nonNull)
                    .mapToInt(v -> v.intValue())
                    .sum();
        }
        return item.getQuantity() != null ? item.getQuantity() : 0;
    }

    private OrderMeta parseOrderMeta(String rawObservations) {
        String base = rawObservations != null ? rawObservations : "";
        String packingRaw = "";
        String shippingRaw = "";
        List<String> baseLines = new ArrayList<>();
        for (String line : base.split("\n")) {
            if (line.startsWith(OPV_PACKING_TAG)) {
                packingRaw = line.substring(OPV_PACKING_TAG.length()).trim();
            } else if (line.startsWith(OPV_SHIPPING_TAG)) {
                shippingRaw = line.substring(OPV_SHIPPING_TAG.length()).trim();
            } else {
                baseLines.add(line);
            }
        }
        List<PackingItem> packingItems = new ArrayList<>();
        if (!packingRaw.isEmpty()) {
            try {
                List<Map<String, Object>> parsed = objectMapper.readValue(packingRaw, new TypeReference<>() {});
                for (Map<String, Object> row : parsed) {
                    BigDecimal quantity = toBigDecimal(row.get("quantity"));
                    BigDecimal unitPrice = toBigDecimal(row.get("unitPrice"));
                    if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                        packingItems.add(new PackingItem(quantity, unitPrice));
                    }
                }
            } catch (Exception ignored) {
                // ignore malformed packing JSON
            }
        }
        BigDecimal shippingCost = BigDecimal.ZERO;
        if (!shippingRaw.isEmpty()) {
            try {
                shippingCost = new BigDecimal(shippingRaw.trim());
            } catch (NumberFormatException ignored) {
                shippingCost = BigDecimal.ZERO;
            }
        }
        return new OrderMeta(String.join("\n", baseLines).trim(), shippingCost, packingItems);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private CustomerEntity loadCustomer(Long customerId) throws ResourceNotFoundException {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }

    private List<CustomerAccountEntryEntity> loadActiveEntries(Long customerId) {
        return entryRepository.findByCustomerIdAndStatusOrderByEntryDateAscIdAsc(customerId, STATUS_ACTIVE);
    }

    private BigDecimal computeBalance(List<CustomerAccountEntryEntity> entries) {
        BigDecimal balance = BigDecimal.ZERO;
        for (CustomerAccountEntryEntity entry : entries) {
            if (!STATUS_ACTIVE.equalsIgnoreCase(entry.getStatus())) {
                continue;
            }
            if (isDebitType(entry.getEntryType())) {
                balance = balance.add(entry.getAmount());
            } else if (isCreditType(entry.getEntryType())) {
                balance = balance.subtract(entry.getAmount());
            }
        }
        return balance.setScale(2, RoundingMode.HALF_UP);
    }

    private static AccountSplit splitBalance(BigDecimal netBalance) {
        BigDecimal net = netBalance != null ? netBalance : BigDecimal.ZERO;
        BigDecimal due = net.compareTo(BigDecimal.ZERO) > 0 ? net.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal credit = net.compareTo(BigDecimal.ZERO) < 0
                ? net.abs().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new AccountSplit(due, credit);
    }

    private record AccountSplit(BigDecimal due, BigDecimal credit) {}

    @Transactional(readOnly = true)
    public boolean isLfVendorOrder(ProductionOrderEntity order) {
        return isLfReceivableOrder(order);
    }

    @Transactional(readOnly = true)
    public BigDecimal estimateVendorOrderTotal(ProductionOrderEntity order) {
        return estimateLfOrderTotal(order);
    }

    private boolean isLfReceivableOrder(ProductionOrderEntity order) {
        if (order == null || order.getSellerName() == null) {
            return false;
        }
        if (!order.getSellerName().toUpperCase(Locale.ROOT).contains("LUIS FELIPE")) {
            return false;
        }
        String type = order.getOrderType() != null ? order.getOrderType().trim().toUpperCase(Locale.ROOT) : "";
        return !"INTERNA".equals(type) && !"CLIENTE_KIOSKO".equals(type);
    }

    private String classifyOrderKind(ProductionOrderEntity order) {
        String type = order.getOrderType() != null ? order.getOrderType().trim().toUpperCase(Locale.ROOT) : "";
        if (isCinchoOrderType(type)) {
            return "OPC";
        }
        return "OPV";
    }

    private static boolean isCinchoOrderType(String orderType) {
        return "CINCHOS".equals(orderType)
                || "CINCHOS_FOSSILES".equals(orderType)
                || "CINCHOS_MARCAS".equals(orderType);
    }

    private static boolean isDebitType(String entryType) {
        return TYPE_CHARGE.equalsIgnoreCase(entryType) || TYPE_OPENING_BALANCE.equalsIgnoreCase(entryType);
    }

    private static boolean isCreditType(String entryType) {
        return TYPE_PAYMENT.equalsIgnoreCase(entryType)
                || TYPE_CREDIT_NOTE.equalsIgnoreCase(entryType)
                || TYPE_RETURN.equalsIgnoreCase(entryType);
    }

    private static String normalizeEntryType(String raw) throws BusinessException {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Tipo de movimiento requerido.");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(TYPE_CHARGE, TYPE_PAYMENT, TYPE_CREDIT_NOTE, TYPE_OPENING_BALANCE, TYPE_RETURN).contains(normalized)) {
            throw new BusinessException("Tipo de movimiento no válido: " + raw);
        }
        return normalized;
    }

    private static boolean matchesSearch(CustomerEntity customer, String searchNorm) {
        return contains(customer.getName(), searchNorm)
                || contains(customer.getLegacyCode(), searchNorm)
                || contains(customer.getNit(), searchNorm)
                || contains(customer.getPhone(), searchNorm)
                || contains(customer.getEmail(), searchNorm);
    }

    private static boolean contains(String value, String searchNorm) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(searchNorm);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal scaleOrNull(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal scalePercentOrNull(BigDecimal value) {
        return value != null ? value.setScale(4, RoundingMode.HALF_UP) : null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String resolveOrderCode(Long productionOrderId) {
        if (productionOrderId == null) {
            return null;
        }
        return productionOrderRepository.findById(productionOrderId)
                .map(ProductionOrderEntity::getCode)
                .orElse(null);
    }

    private CustomerAccountEntryResponse toEntryResponse(CustomerAccountEntryEntity entity) {
        return CustomerAccountEntryResponse.builder()
                .id(entity.getId())
                .customerId(entity.getCustomerId())
                .entryType(entity.getEntryType())
                .entryDate(entity.getEntryDate())
                .amount(entity.getAmount())
                .reference(entity.getReference())
                .description(entity.getDescription())
                .paymentMethod(entity.getPaymentMethod())
                .movementConceptCode(entity.getMovementConceptCode())
                .receiptNumber(entity.getReceiptNumber())
                .collectionDate(entity.getCollectionDate())
                .paymentDiscountAmount(entity.getPaymentDiscountAmount())
                .paymentDiscountPercent(entity.getPaymentDiscountPercent())
                .grossCollectedAmount(entity.getGrossCollectedAmount())
                .appliedToEntryId(entity.getAppliedToEntryId())
                .invoiceNumber(entity.getInvoiceNumber())
                .documentNumber(entity.getDocumentNumber())
                .returnVoucherNumber(entity.getReturnVoucherNumber())
                .returnDate(entity.getReturnDate())
                .returnReason(entity.getReturnReason())
                .productionOrderId(entity.getProductionOrderId())
                .productionOrderCode(resolveOrderCode(entity.getProductionOrderId()))
                .partialReleaseId(entity.getPartialReleaseId())
                .productShipmentId(entity.getProductShipmentId())
                .vendorShipmentNumber(entity.getVendorShipmentNumber())
                .orderKind(entity.getOrderKind())
                .status(entity.getStatus())
                .voidedAt(entity.getVoidedAt())
                .voidedBy(entity.getVoidedBy())
                .voidedByName(resolveUserName(entity.getVoidedBy()))
                .voidReason(entity.getVoidReason())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(resolveUserName(entity.getCreatedBy()))
                .build();
    }

    private String resolveUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(u -> {
                    String full = String.join(" ",
                            Optional.ofNullable(u.getFirstName()).orElse("").trim(),
                            Optional.ofNullable(u.getLastName()).orElse("").trim()).trim();
                    return full.isEmpty() ? u.getUsername() : full;
                })
                .orElse(null);
    }

    private static RouteMeta resolveRouteMeta(String routeLocationCode) {
        if (routeLocationCode == null || routeLocationCode.isBlank()) {
            return new RouteMeta(null, null, null);
        }
        return DeliveryRouteCatalog.parseRouteLocationCode(routeLocationCode)
                .map(p -> new RouteMeta(p.regionCode(), p.routeNumber(), p.label()))
                .orElse(new RouteMeta(null, null, routeLocationCode.trim().toUpperCase(Locale.ROOT)));
    }

    private record RouteMeta(String regionCode, Integer routeNumber, String label) {
    }

    private static class OrderMeta {
        private final String baseObservations;
        private final BigDecimal shippingCost;
        private final List<PackingItem> packingItems;

        private OrderMeta(String baseObservations, BigDecimal shippingCost, List<PackingItem> packingItems) {
            this.baseObservations = baseObservations;
            this.shippingCost = shippingCost != null ? shippingCost : BigDecimal.ZERO;
            this.packingItems = packingItems != null ? packingItems : List.of();
        }
    }

    private static class PackingItem {
        private final BigDecimal quantity;
        private final BigDecimal unitPrice;

        private PackingItem(BigDecimal quantity, BigDecimal unitPrice) {
            this.quantity = quantity != null ? quantity : BigDecimal.ZERO;
            this.unitPrice = unitPrice != null ? unitPrice : BigDecimal.ZERO;
        }
    }
}
