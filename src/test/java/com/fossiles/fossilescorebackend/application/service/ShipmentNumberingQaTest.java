package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OpcShipmentGenerateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseLineRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductDistributionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ShipmentNumberCounterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ShipmentNumberCounterRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class ShipmentNumberingQaTest {

    private static final Pattern ENVP = Pattern.compile("^ENVP-(\\d{5})$");
    private static final Pattern ENVI = Pattern.compile("^ENVI-(\\d{5})$");
    private static final Pattern ENVL = Pattern.compile("^ENVL-(\\d{5})$");
    private static final Pattern ENV_SEQ = Pattern.compile("^.+-ENV-(\\d{5})$");

    @Autowired private ProductDistributionService distributionService;
    @Autowired private ProductionOrderPartialReleaseService partialReleaseService;
    @Autowired private ShipmentNumberCounterService counterService;
    @Autowired private OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductionOrderItemRepository items;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;
    @Autowired private LocationRepository locations;
    @Autowired private ProductShipmentRepository shipments;
    @Autowired private ShipmentNumberCounterRepository counters;
    @Autowired private OnlineSaleRepository onlineSales;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private SecurityUtil securityUtil;

    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(22L);
        color = colors.save(ColorEntity.builder().name("QA-NUM-" + tag()).build());
    }

    @Test
    void envpAssignedAtCreationAndConsecutive() throws Exception {
        ProductShipmentResponse first = createLfOpvShipment();
        ProductShipmentResponse second = createLfOpvShipment();
        assertThat(first.getShipmentNumber()).matches(ENVP);
        assertThat(second.getShipmentNumber()).matches(ENVP);
        assertThat(envpValue(second.getShipmentNumber())).isEqualTo(envpValue(first.getShipmentNumber()) + 1);
    }

    @Test
    void eachPartialGetsOwnEnvpWhileOrderKeepsFirst() throws Exception {
        ProductEntity product = saveProduct("PRT");
        ProductionOrderEntity order = saveOrder("OPV-PRT", "MARCAS", "Luis Felipe", "Cliente QA");
        ProductionOrderItemEntity item = saveItem(order, product, 10);

        PartialReleaseResponse firstRelease = createConfirmedPartial(order, item, 3, "P1");
        ProductShipmentResponse first = partialReleaseService.generateShipment(
                firstRelease.getId(),
                OpcShipmentGenerateRequest.builder().destinationAddress("Ciudad").build());
        PartialReleaseResponse secondRelease = createConfirmedPartial(order, item, 2, "P2");
        ProductShipmentResponse second = partialReleaseService.generateShipment(
                secondRelease.getId(),
                OpcShipmentGenerateRequest.builder().destinationAddress("Ciudad").build());

        assertThat(first.getShipmentNumber()).matches(ENVP);
        assertThat(second.getShipmentNumber()).matches(ENVP);
        assertThat(second.getShipmentNumber()).isNotEqualTo(first.getShipmentNumber());
        ProductionOrderEntity reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getVendorShipmentNumber()).isEqualTo(first.getShipmentNumber());
    }

    @Test
    void lfOpcCounterIsGlobalAcrossOrders() throws Exception {
        long before = counterValue(ShipmentNumberCounterService.LF_OPC);
        createLfCinchoShipment();
        long afterFirst = counterValue(ShipmentNumberCounterService.LF_OPC);
        createLfCinchoShipment();
        long afterSecond = counterValue(ShipmentNumberCounterService.LF_OPC);
        assertThat(afterFirst).isEqualTo(before + 1);
        assertThat(afterSecond).isEqualTo(before + 2);
    }

    @Test
    void envpAndLfOpcCountersAreIndependent() throws Exception {
        long envpBefore = counterValue(ShipmentNumberCounterService.LF_ENVP);
        long opcBefore = counterValue(ShipmentNumberCounterService.LF_OPC);

        createLfOpvShipment();
        assertThat(counterValue(ShipmentNumberCounterService.LF_ENVP)).isEqualTo(envpBefore + 1);
        assertThat(counterValue(ShipmentNumberCounterService.LF_OPC)).isEqualTo(opcBefore);

        counterService.nextOpcSequence();
        assertThat(counterValue(ShipmentNumberCounterService.LF_ENVP)).isEqualTo(envpBefore + 1);
        assertThat(counterValue(ShipmentNumberCounterService.LF_OPC)).isEqualTo(opcBefore + 1);
    }

    @Test
    void shipmentTransactionRollbackDoesNotLeaveAGap() throws Exception {
        ProductEntity product = saveProduct("RB");
        ProductionOrderEntity order = saveOrder("OPV-RB", "MARCAS", "Luis Felipe", "Cliente QA");
        saveItem(order, product, 4);
        long before = counterValue(ShipmentNumberCounterService.LF_ENVP);

        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> inner.execute(status -> {
            try {
                distributionService.createOrUpdateShipmentForProductionOrder(order.getId(), lfRequest(product, "1"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(counterValue(ShipmentNumberCounterService.LF_ENVP)).isEqualTo(before);
        assertThat(shipments.findByProductionOrderId(order.getId())).isEmpty();
    }

    @Test
    void sequentialCreatesHaveNoDuplicateEnvp() throws Exception {
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            numbers.add(createLfOpvShipment().getShipmentNumber());
        }
        assertThat(numbers).hasSize(5);
        assertThat(numbers).allMatch(n -> ENVP.matcher(n).matches());
    }

    /**
     * H2 serializes pessimistic locks; this only checks uniqueness under concurrent creates.
     * Real concurrency proof needs Testcontainers/Postgres.
     */
    @Test
    void parallelCreateOnH2DoesNotDuplicateEnvp() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    ProductEntity product = saveProduct("PAR");
                    ProductionOrderEntity order = saveOrder("OPV-PAR", "MARCAS", "Luis Felipe", "Cliente QA");
                    saveItem(order, product, 2);
                    return distributionService.createOrUpdateShipmentForProductionOrder(
                            order.getId(), lfRequest(product, "1")).getShipmentNumber();
                }));
            }
            start.countDown();
            Set<String> numbers = new HashSet<>();
            for (Future<String> future : futures) {
                numbers.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(numbers).hasSize(threads);
            assertThat(numbers).allMatch(n -> ENVP.matcher(n).matches());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void distributionPerLocationNumberingUnchanged() throws Exception {
        LocationEntity kiosk = locations.save(LocationEntity.builder()
                .code(("D" + tag()).substring(0, 9))
                .name("Kiosko dist QA")
                .categoria("KIOSKO")
                .build());
        ProductEntity product = saveProduct("DIST");
        ProductDistributionResponse distribution = distributionService.createDistribution(
                ProductDistributionRequest.builder().distributionDate(LocalDate.now()).build());

        ProductShipmentResponse first = distributionService.createOrUpdateShipment(
                distribution.getId(), kioskShipment(kiosk, product));
        distributionService.confirmShipmentDraft(first.getId());
        ProductShipmentResponse second = distributionService.createOrUpdateShipment(
                distribution.getId(), kioskShipment(kiosk, product));

        String prefix = kiosk.getCode() + "-ENV-";
        assertThat(first.getShipmentNumber()).isEqualTo(prefix + "00001");
        assertThat(second.getShipmentNumber()).isEqualTo(prefix + "00002");
        assertThat(first.getShipmentNumber()).doesNotStartWith("ENVP-");
    }

    @Test
    void enviNumberingUnchanged() throws Exception {
        long envpBefore = counterValue(ShipmentNumberCounterService.LF_ENVP);
        long opcBefore = counterValue(ShipmentNumberCounterService.LF_OPC);
        ProductShipmentResponse first = createInternaShipment();
        ProductShipmentResponse second = createInternaShipment();
        assertThat(first.getShipmentNumber()).matches(ENVI);
        assertThat(second.getShipmentNumber()).matches(ENVI);
        assertThat(enviValue(second.getShipmentNumber())).isEqualTo(enviValue(first.getShipmentNumber()) + 1);
        assertThat(first.getShipmentNumber()).doesNotStartWith("ENVP-");
        assertThat(counterValue(ShipmentNumberCounterService.LF_ENVP)).isEqualTo(envpBefore);
        assertThat(counterValue(ShipmentNumberCounterService.LF_OPC)).isEqualTo(opcBefore);
    }

    @Test
    void envlNumberingUnchanged() {
        OnlineSaleEntity first = onlineSales.save(OnlineSaleEntity.builder()
                .saleNumber("ON-" + tag())
                .customerName("Cliente ENVL")
                .status("PENDIENTE")
                .saleDate(LocalDate.now())
                .build());
        onlineSaleShipmentNumberService.assignIfMissing(first);
        first = onlineSales.save(first);

        OnlineSaleEntity second = onlineSales.save(OnlineSaleEntity.builder()
                .saleNumber("ON-" + tag())
                .customerName("Cliente ENVL 2")
                .status("PENDIENTE")
                .saleDate(LocalDate.now())
                .build());
        onlineSaleShipmentNumberService.assignIfMissing(second);
        second = onlineSales.save(second);

        assertThat(first.getShipmentNumber()).matches(ENVL);
        assertThat(second.getShipmentNumber()).matches(ENVL);
        assertThat(envlValue(second.getShipmentNumber())).isEqualTo(envlValue(first.getShipmentNumber()) + 1);
        assertThat(first.getShipmentNumber()).doesNotStartWith("ENVP-");
    }

    @Test
    void entreCuerosPerOrderNumberingUnchanged() throws Exception {
        long opcBefore = counterValue(ShipmentNumberCounterService.LF_OPC);
        ProductEntity product = saveProduct("EC");
        ProductionOrderEntity order = saveOrder("OPV-EC", "MARCAS", "Vendedor", "Entre Cueros");
        saveItem(order, product, 6);

        ProductShipmentResponse first = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(), lfRequest(product, "1"));
        distributionService.confirmShipmentDraft(first.getId());
        ProductShipmentResponse second = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(), lfRequest(product, "1"));

        String prefix = order.getCode().trim().toUpperCase() + "-ENV-";
        assertThat(first.getShipmentNumber()).isEqualTo(prefix + "00001");
        assertThat(second.getShipmentNumber()).isEqualTo(prefix + "00002");
        assertThat(first.getShipmentNumber()).doesNotStartWith("ENVP-");
        assertThat(counterValue(ShipmentNumberCounterService.LF_OPC)).isEqualTo(opcBefore);
    }

    @Test
    void nonLfOpcPerOrderNumberingUnchanged() throws Exception {
        long opcBefore = counterValue(ShipmentNumberCounterService.LF_OPC);
        ProductEntity product = saveProduct("OPC");
        ProductionOrderEntity order = saveOrder("OPC-NL", "CINCHOS", "Tienda", "Cliente OPC");
        saveItem(order, product, 4);

        ProductShipmentResponse first = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(), lfRequest(product, "1"));
        distributionService.confirmShipmentDraft(first.getId());
        ProductShipmentResponse second = distributionService.createOrUpdateShipmentForProductionOrder(
                order.getId(), lfRequest(product, "1"));

        Matcher firstSeq = ENV_SEQ.matcher(first.getShipmentNumber());
        Matcher secondSeq = ENV_SEQ.matcher(second.getShipmentNumber());
        assertThat(firstSeq.matches()).isTrue();
        assertThat(secondSeq.matches()).isTrue();
        assertThat(Integer.parseInt(secondSeq.group(1))).isEqualTo(Integer.parseInt(firstSeq.group(1)) + 1);
        assertThat(first.getShipmentNumber()).startsWith(order.getCode().trim().toUpperCase() + "-ENV-");
        assertThat(first.getShipmentNumber()).doesNotStartWith("ENVP-");
        assertThat(counterValue(ShipmentNumberCounterService.LF_OPC)).isEqualTo(opcBefore);
    }

    @Test
    @Disabled("pending decision on LF cincho format")
    void lfCinchoShipmentNumberFormat_pendingDecision() {
        // Index/Eduardo: LF cincho shipments may use {ENVP}-ENV-n or {OPCODE}-ENV-n.
        // Do not assert either format until that decision lands.
    }

    private ProductShipmentResponse createLfOpvShipment() throws Exception {
        ProductEntity product = saveProduct("LF");
        ProductionOrderEntity order = saveOrder("OPV-LF", "MARCAS", "Luis Felipe", "Cliente QA");
        saveItem(order, product, 3);
        return distributionService.createOrUpdateShipmentForProductionOrder(order.getId(), lfRequest(product, "1"));
    }

    private ProductShipmentResponse createLfCinchoShipment() throws Exception {
        ProductEntity product = saveProduct("LFC");
        ProductionOrderEntity order = saveOrder("OPC-LF", "CINCHOS", "Luis Felipe", "Cliente QA");
        saveItem(order, product, 2);
        return distributionService.createOrUpdateShipmentForProductionOrder(order.getId(), lfRequest(product, "1"));
    }

    private ProductShipmentResponse createInternaShipment() throws Exception {
        ProductEntity product = saveProduct("IN");
        ProductionOrderEntity order = saveOrder("OPI-IN", "INTERNA", "Bodega", "Colaborador");
        saveItem(order, product, 2);
        return distributionService.createOrUpdateShipmentForProductionOrder(order.getId(), lfRequest(product, "1"));
    }

    private PartialReleaseResponse createConfirmedPartial(
            ProductionOrderEntity order, ProductionOrderItemEntity item, int qty, String label) throws Exception {
        return partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label(label)
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(item.getId())
                                .quantity(qty)
                                .build()))
                        .build());
    }

    private ProductShipmentRequest lfRequest(ProductEntity product, String qty) {
        return ProductShipmentRequest.builder()
                .destinationAddress("Ciudad")
                .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(product.getId())
                        .colorId(color.getId())
                        .quantity(new BigDecimal(qty))
                        .build()))
                .build();
    }

    private ProductShipmentRequest kioskShipment(LocationEntity kiosk, ProductEntity product) {
        return ProductShipmentRequest.builder()
                .locationId(kiosk.getId())
                .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(product.getId())
                        .colorId(color.getId())
                        .quantity(BigDecimal.ONE)
                        .build()))
                .build();
    }

    private ProductionOrderEntity saveOrder(String code, String orderType, String sellerName, String customerName) {
        return orders.save(ProductionOrderEntity.builder()
                .code(code + "-" + tag())
                .orderType(orderType)
                .status("IN_PROGRESS")
                .sellerName(sellerName)
                .customerName(customerName)
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity saveItem(ProductionOrderEntity order, ProductEntity product, int quantity) {
        return items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .warehouseReceivedQty(0)
                .unitPrice(BigDecimal.valueOf(100))
                .build());
    }

    private ProductEntity saveProduct(String code) {
        return products.save(ProductEntity.builder()
                .code(code + "-" + tag())
                .name("Producto " + code)
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
    }

    private long counterValue(String type) {
        return counters.findById(type).map(ShipmentNumberCounterEntity::getLastValue).orElse(0L);
    }

    private static int envpValue(String number) {
        Matcher matcher = ENVP.matcher(number);
        assertThat(matcher.matches()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static int enviValue(String number) {
        Matcher matcher = ENVI.matcher(number);
        assertThat(matcher.matches()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static int envlValue(String number) {
        Matcher matcher = ENVL.matcher(number);
        assertThat(matcher.matches()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
