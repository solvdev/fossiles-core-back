package com.fossiles.fossilescorebackend.application.service.customeraccount;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountReceivableSearchResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountStatementLineResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogRowResponse;
import com.fossiles.fossilescorebackend.application.service.CustomerAccountService;
import com.fossiles.fossilescorebackend.application.service.OpvShipmentCatalogService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Synthetic LF receivables data (customers, OPV/OPC orders, partial releases, shipments, account entries).
 * Entries go through {@link CustomerAccountService#createEntry} unless a test needs a state that path refuses
 * to produce (legacy rows), in which case {@link #insert} writes the row directly.
 */
final class LfReceivablesFixture {

    static final LocalDate ENTRY_DATE = LocalDate.of(2026, 9, 1);
    static final String SELLER_LF = "LUIS FELIPE";
    static final String TYPE_OPC = "MARCAS";
    static final String TYPE_OPV = "OPV";
    static final String CHARGE_REQUIRES_ORDER = "El cargo requiere una orden de producción.";
    static final String CHARGE_NEEDS_SHIPMENT = "El cargo se genera por envío despachado. Indique el envío.";
    static final String SHIPMENT_NOT_DISPATCHED = "Solo un envío despachado genera cargo. Estado actual: ";
    static final String ORDER_ALREADY_CHARGED = "La orden ya tiene un cargo por orden completa; su saldo se limita a lo "
            + "despachado. Anule ese cargo si debe cobrarse por envío.";
    static final String DUPLICATE_SHIPMENT_CHARGE = "Ya existe un cargo activo para este envío.";
    static final String CREDIT_MUST_APPLY = "El pago, la nota de crédito o la devolución debe aplicarse al cargo de la orden.";
    static final String ADJUSTMENT_NEEDS_SHIPMENT = "El ajuste de envío requiere un envío real del parcial.";

    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final ProductionOrderRepository orderRepository;
    private final ProductionOrderItemRepository itemRepository;
    private final ProductionOrderPartialReleaseRepository releaseRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository detailRepository;
    private final CustomerAccountEntryRepository entryRepository;
    final CustomerAccountService accounts;
    final OpvShipmentCatalogService catalog;

    private ProductEntity product;

    LfReceivablesFixture(ApplicationContext ctx) {
        customerRepository = ctx.getBean(CustomerRepository.class);
        productRepository = ctx.getBean(ProductRepository.class);
        orderRepository = ctx.getBean(ProductionOrderRepository.class);
        itemRepository = ctx.getBean(ProductionOrderItemRepository.class);
        releaseRepository = ctx.getBean(ProductionOrderPartialReleaseRepository.class);
        shipmentRepository = ctx.getBean(ProductShipmentRepository.class);
        detailRepository = ctx.getBean(ProductShipmentDetailRepository.class);
        entryRepository = ctx.getBean(CustomerAccountEntryRepository.class);
        accounts = ctx.getBean(CustomerAccountService.class);
        catalog = ctx.getBean(OpvShipmentCatalogService.class);
    }

    static BigDecimal q(String amount) {
        return new BigDecimal(amount);
    }

    CustomerEntity customer(String legacyCode) {
        return customerRepository.save(CustomerEntity.builder()
                .name("Cliente sintetico " + legacyCode)
                .legacyCode(legacyCode)
                .status("ACTIVE")
                .build());
    }

    /** LF order whose estimate is the sum of one item per price; MARCAS classifies as OPC, OPV as OPV. */
    ProductionOrderEntity order(CustomerEntity customer, String code, String orderType, String... itemPrices) {
        ProductionOrderEntity order = orderRepository.save(ProductionOrderEntity.builder()
                .code(code)
                .orderType(orderType)
                .customerId(customer.getId())
                .sellerName(SELLER_LF)
                .vendorShipmentNumber("ENVP-" + code)
                .status("IN_PROGRESS")
                .startDate(ENTRY_DATE)
                .build());
        for (String price : itemPrices) {
            itemRepository.save(ProductionOrderItemEntity.builder()
                    .productionOrderId(order.getId())
                    .productId(product().getId())
                    .quantity(1)
                    .unitPrice(q(price))
                    .build());
        }
        return order;
    }

    ProductionOrderPartialReleaseEntity release(ProductionOrderEntity order, int sequenceNum) {
        return releaseRepository.save(ProductionOrderPartialReleaseEntity.builder()
                .productionOrderId(order.getId())
                .sequenceNum(sequenceNum)
                .label("Parcial " + sequenceNum)
                .status("RELEASED")
                .build());
    }

    /** Shipment with a single frozen-price line, so both read models estimate it at {@code estimate}. */
    ProductShipmentEntity shipment(
            ProductionOrderEntity order, ProductionOrderPartialReleaseEntity release, String number, String estimate) {
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .productionOrderId(order.getId())
                .partialReleaseId(release != null ? release.getId() : null)
                .shipmentNumber(number)
                .status("SENT")
                .build());
        detailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product().getId())
                .sizeLabel("")
                .quantity(BigDecimal.ONE)
                .unitPrice(q(estimate))
                .build());
        return shipment;
    }

    /** What happens when a shipment is dispatched: its charge (and shipping adjustment) is born. Empty if none applies. */
    java.util.Optional<CustomerAccountEntryEntity> dispatch(ProductShipmentEntity shipment) throws Exception {
        return accounts.createChargeForDispatchedShipment(shipment.getId());
    }

    /** Re-prices the single line of a fixture shipment (what an edit of the shipment products does). */
    void setLinePrice(ProductShipmentEntity shipment, String unitPrice) {
        for (ProductShipmentDetailEntity detail : detailRepository.findByShipmentId(shipment.getId())) {
            detail.setUnitPrice(q(unitPrice));
            detailRepository.save(detail);
        }
    }

    ProductShipmentEntity withStatus(ProductShipmentEntity shipment, String status) {
        shipment.setStatus(status);
        return shipmentRepository.save(shipment);
    }

    List<CustomerAccountEntryEntity> activeAdjustments(CustomerEntity customer) {
        return entryRepository.findByCustomerIdAndStatusOrderByEntryDateAscIdAsc(customer.getId(), "ACTIVE").stream()
                .filter(e -> "CHARGE_ADJUSTMENT".equals(e.getEntryType()))
                .toList();
    }

    CustomerAccountEntryResponse create(CustomerEntity customer, CustomerAccountEntryRequest request) throws Exception {
        return accounts.createEntry(customer.getId(), request);
    }

    /**
     * Charge of the pre-dispatch model: one CHARGE for the whole order. The API no longer creates it (a charge is born
     * when a shipment is dispatched), so it is inserted directly, like the legacy rows it stands for. The order is
     * fully dispatched first so the visible cartera does not clip it. A call that names a release or a shipment goes
     * through the API, which now only accepts a dispatched shipment.
     */
    CustomerAccountEntryResponse charge(CustomerEntity customer, ProductionOrderEntity order,
            ProductionOrderPartialReleaseEntity release, ProductShipmentEntity shipment, String amount) throws Exception {
        if (release != null || shipment != null) {
            return create(customer, chargeRequest(order, release, shipment, amount));
        }
        dispatchWholeOrder(order);
        CustomerAccountEntryEntity saved = insert(legacyCharge(customer, order, null, null, amount)
                .vendorShipmentNumber(order.getVendorShipmentNumber())
                .invoiceNumber(order.getVendorShipmentNumber())
                .documentNumber(order.getCode())
                .orderKind(accounts.resolveReceivableOrderKind(order))
                .build());
        return CustomerAccountEntryResponse.builder()
                .id(saved.getId())
                .customerId(saved.getCustomerId())
                .entryType(saved.getEntryType())
                .amount(saved.getAmount())
                .productionOrderId(saved.getProductionOrderId())
                .status(saved.getStatus())
                .build();
    }

    /** A SENT shipment holding the whole order (one line priced at the sum of the items), unless it already has one. */
    void dispatchWholeOrder(ProductionOrderEntity order) {
        if (!shipmentRepository.findByProductionOrderId(order.getId()).isEmpty()) {
            return;
        }
        BigDecimal total = itemRepository.findByProductionOrderId(order.getId()).stream()
                .map(ProductionOrderItemEntity::getUnitPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .productionOrderId(order.getId())
                .shipmentNumber("ENVP-FULL-" + order.getCode())
                .status("SENT")
                .build());
        detailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product().getId())
                .sizeLabel("")
                .quantity(BigDecimal.ONE)
                .unitPrice(total)
                .build());
    }

    static CustomerAccountEntryRequest chargeRequest(ProductionOrderEntity order,
            ProductionOrderPartialReleaseEntity release, ProductShipmentEntity shipment, String amount) {
        CustomerAccountEntryRequest request = baseRequest("CHARGE", amount);
        request.setProductionOrderId(order != null ? order.getId() : null);
        request.setPartialReleaseId(release != null ? release.getId() : null);
        request.setProductShipmentId(shipment != null ? shipment.getId() : null);
        return request;
    }

    /**
     * PAYMENT, CREDIT_NOTE or RETURN with the minimum fields each type requires. {@code amount} is sent as the
     * gross for PAYMENT/RETURN; a linked PAYMENT uses concept 11 (descarga), an unlinked one concept 4 (efectivo).
     */
    static CustomerAccountEntryRequest creditRequest(String entryType, Long appliedToEntryId, String amount) {
        CustomerAccountEntryRequest request = baseRequest(entryType, amount);
        request.setAppliedToEntryId(appliedToEntryId);
        switch (entryType) {
            case "PAYMENT" -> {
                request.setGrossCollectedAmount(q(amount));
                request.setMovementConceptCode(appliedToEntryId != null ? "11" : "4");
                request.setReceiptNumber("REC-" + amount);
                request.setCollectionDate(ENTRY_DATE);
            }
            case "CREDIT_NOTE" -> request.setMovementConceptCode("2");
            case "RETURN" -> {
                request.setGrossCollectedAmount(q(amount));
                request.setReturnVoucherNumber("DEV-" + amount);
                request.setReturnDate(ENTRY_DATE);
            }
            default -> throw new IllegalArgumentException(entryType);
        }
        return request;
    }

    private static CustomerAccountEntryRequest baseRequest(String entryType, String amount) {
        CustomerAccountEntryRequest request = new CustomerAccountEntryRequest();
        request.setEntryType(entryType);
        request.setEntryDate(ENTRY_DATE);
        request.setAmount(q(amount));
        return request;
    }

    CustomerAccountEntryEntity insert(CustomerAccountEntryEntity entry) {
        return entryRepository.save(entry);
    }

    static CustomerAccountEntryEntity.CustomerAccountEntryEntityBuilder legacyCharge(CustomerEntity customer,
            ProductionOrderEntity order, ProductionOrderPartialReleaseEntity release, ProductShipmentEntity shipment,
            String amount) {
        return CustomerAccountEntryEntity.builder()
                .customerId(customer.getId())
                .entryType("CHARGE")
                .status("ACTIVE")
                .entryDate(ENTRY_DATE)
                .amount(q(amount))
                .productionOrderId(order.getId())
                .partialReleaseId(release != null ? release.getId() : null)
                .productShipmentId(shipment != null ? shipment.getId() : null);
    }

    List<CustomerAccountEntryEntity> entries(CustomerEntity customer) {
        return entryRepository.findByCustomerIdOrderByEntryDateAscIdAsc(customer.getId());
    }

    List<CustomerAccountEntryEntity> activeCharges(CustomerEntity customer) {
        return entryRepository.findByCustomerIdAndStatusOrderByEntryDateAscIdAsc(customer.getId(), "ACTIVE").stream()
                .filter(e -> "CHARGE".equals(e.getEntryType()))
                .toList();
    }

    CustomerAccountEntryEntity entry(Long id) {
        return entryRepository.findById(id).orElseThrow();
    }

    OpvShipmentCatalogRowResponse catalogRow(CustomerEntity customer, ProductShipmentEntity shipment) {
        return catalogRows(customer).stream()
                .filter(r -> Objects.equals(r.getProductShipmentId(), shipment.getId()))
                .findFirst()
                .orElseThrow();
    }

    List<OpvShipmentCatalogRowResponse> catalogRows(CustomerEntity customer) {
        return catalog.search(null, null, null, customer.getId(), null, null, null, 0);
    }

    /** Rows of the receivables search ("cartera") for one shipment, including orphan-charge rows. */
    List<CustomerAccountReceivableSearchResponse> receivableRows(CustomerEntity customer, ProductShipmentEntity shipment) {
        return receivableRows(customer).stream()
                .filter(r -> Objects.equals(r.getProductShipmentId(), shipment.getId()))
                .toList();
    }

    List<CustomerAccountReceivableSearchResponse> receivableRows(CustomerEntity customer) {
        return accounts.searchReceivables(null, null, null, null, null, null, null, null, false, 0).stream()
                .filter(r -> Objects.equals(r.getCustomerId(), customer.getId()))
                .toList();
    }

    /** Open balance of one charge as reported on the statement line ({@code chargeBalanceDue}). */
    BigDecimal chargeBalance(CustomerEntity customer, Long chargeId) throws Exception {
        return statementLine(customer, chargeId).getChargeBalanceDue();
    }

    CustomerAccountStatementLineResponse statementLine(CustomerEntity customer, Long entryId) throws Exception {
        return accounts.getStatement(customer.getId(), null, null).getLines().stream()
                .filter(l -> l.getId().equals(entryId))
                .findFirst()
                .orElseThrow();
    }

    BigDecimal balance(CustomerEntity customer) throws Exception {
        return accounts.getBalance(customer.getId()).getBalance();
    }

    ProductShipmentEntity withShipping(ProductShipmentEntity shipment, String shippingCost) {
        shipment.setShippingCost(shippingCost == null ? null : q(shippingCost));
        return shipmentRepository.save(shipment);
    }

    ProductShipmentEntity withSentAt(ProductShipmentEntity shipment, LocalDate sentOn) {
        shipment.setSentAt(sentOn.atStartOfDay());
        return shipmentRepository.save(shipment);
    }

    void setCreditDays(CustomerEntity customer, int days) {
        customer.setCreditDays(days);
        customerRepository.saveAndFlush(customer);
    }

    static CustomerAccountEntryRequest adjustmentRequest(Long productShipmentId) {
        CustomerAccountEntryRequest request = baseRequest("CHARGE_ADJUSTMENT", "1.00");
        request.setProductShipmentId(productShipmentId);
        return request;
    }

    static CustomerAccountEntryRequest openingRequest(String amount, LocalDate entryDate) {
        CustomerAccountEntryRequest request = baseRequest("OPENING_BALANCE", amount);
        request.setEntryDate(entryDate);
        return request;
    }

    private ProductEntity product() {
        if (product == null) {
            product = productRepository.save(ProductEntity.builder()
                    .code("LF-SYNTH-PROD")
                    .name("Producto sintetico LF")
                    .requiresMaterials(false)
                    .build());
        }
        return product;
    }
}
