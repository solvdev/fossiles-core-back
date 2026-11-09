package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.BoxLineRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.DispatchRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.OrderDetail;
import com.fossiles.fossilescorebackend.application.exception.LfPackingConflictException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LfPackingBoxLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LfPackingBoxLineRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LfPackingBoxRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ShipmentNumberCounterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ShipmentNumberCounterRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Dos teléfonos de bodega a la vez sobre la misma orden. Sin @Transactional en la prueba: cada llamada abre su propia
 * transacción y el {@code SELECT ... FOR UPDATE} sobre la orden las serializa.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:lf_packing_concurrency;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1"
})
class LfPackingConcurrencyTest {

    @Autowired
    private LfPackingService packing;
    @Autowired
    private ProductionOrderRepository orderRepository;
    @Autowired
    private ProductionOrderItemRepository itemRepository;
    @Autowired
    private ProductionOrderWarehouseUnitRepository unitRepository;
    @Autowired
    private LfPackingBoxRepository boxRepository;
    @Autowired
    private LfPackingBoxLineRepository boxLineRepository;
    @Autowired
    private ProductShipmentRepository shipmentRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private ShipmentNumberCounterRepository counters;

    @MockitoBean
    private SecurityUtil securityUtil;

    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(9L);
        when(securityUtil.getCurrentUser()).thenReturn(Optional.of(UserEntity.builder()
                .username("telefono")
                .roles(Set.of(RoleEntity.builder().name("Bodega").build()))
                .build()));
        if (counters.findById("LF_ENVP").isEmpty()) {
            counters.save(ShipmentNumberCounterEntity.builder().type("LF_ENVP").lastValue(100L).build());
            counters.save(ShipmentNumberCounterEntity.builder().type("LF_OPC").lastValue(500L).build());
        }
        color = colorRepository.save(ColorEntity.builder().name("NEGRO-CONC-" + System.nanoTime()).build());
    }

    @Test
    @DisplayName("Dos teléfonos agregan a la vez a cajas distintas: lo último que queda lo gana uno solo")
    void twoPhonesCannotOverbookTheLastPieces() throws Exception {
        ProductionOrderEntity order = order("OPV-CONC-01");
        ProductionOrderItemEntity item = item(order, 5);
        Long boxA = packing.createBox(order.getId(), null).getBoxes().get(0).getId();
        OrderDetail withB = packing.createBox(order.getId(), null);
        Long boxB = withB.getBoxes().get(1).getId();

        List<Object> outcomes = runAtSameTime(
                () -> packing.changeLine(order.getId(), boxA, line(item, 3)),
                () -> packing.changeLine(order.getId(), boxB, line(item, 3)));

        long conflicts = outcomes.stream().filter(o -> o instanceof LfPackingConflictException).count();
        long ok = outcomes.stream().filter(o -> o instanceof OrderDetail).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        int inBoxes = boxLineRepository.findByBoxIdIn(List.of(boxA, boxB)).stream()
                .mapToInt(LfPackingBoxLineEntity::getQuantity).sum();
        assertThat(inBoxes).isEqualTo(3);
        LfPackingConflictException conflict = (LfPackingConflictException) outcomes.stream()
                .filter(o -> o instanceof LfPackingConflictException).findFirst().orElseThrow();
        assertThat(conflict.getCode()).isEqualTo("QTY_UNAVAILABLE");
        assertThat(conflict.getMessage()).contains("Solo quedan 2");
    }

    @Test
    @DisplayName("Dos teléfonos despachan la misma caja a la vez: un solo envío, el otro recibe 409")
    void twoPhonesCannotDispatchTheSameBoxTwice() throws Exception {
        ProductionOrderEntity order = order("OPV-CONC-02");
        ProductionOrderItemEntity item = item(order, 4);
        for (int i = 1; i <= 4; i++) {
            unitRepository.save(ProductionOrderWarehouseUnitEntity.builder()
                    .productionOrderId(order.getId())
                    .productionOrderItemId(item.getId())
                    .unitLabel("#" + i)
                    .colorId(color.getId())
                    .sizeKey("")
                    .unitSeq(i)
                    .receiptStatus("RECEIVED")
                    .receivedAt(LocalDateTime.now())
                    .build());
        }
        Long box = packing.createBox(order.getId(), null).getBoxes().get(0).getId();
        packing.changeLine(order.getId(), box, line(item, 4));
        packing.closeBox(order.getId(), box);

        DispatchRequest request = DispatchRequest.builder().boxIds(List.of(box)).build();
        List<Object> outcomes = runAtSameTime(
                () -> packing.dispatch(order.getId(), request),
                () -> packing.dispatch(order.getId(), request));

        assertThat(outcomes.stream().filter(o -> !(o instanceof Throwable)).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o instanceof LfPackingConflictException).count()).isEqualTo(1);
        assertThat(shipmentRepository.findByProductionOrderId(order.getId())).hasSize(1);
        assertThat(boxRepository.findById(box).orElseThrow().getStatus()).isEqualTo("DISPATCHED");
    }

    // ───────────────────────────── Datos ─────────────────────────────

    private List<Object> runAtSameTime(Callable<Object> first, Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Callable<Object> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static BoxLineRequest line(ProductionOrderItemEntity item, int quantity) {
        return BoxLineRequest.builder().productionOrderItemId(item.getId()).quantity(quantity).build();
    }

    private ProductionOrderEntity order(String code) {
        return orderRepository.save(ProductionOrderEntity.builder()
                .code(code)
                .orderType("MARCAS")
                .status("IN_PROGRESS")
                .sellerName("Luis Felipe")
                .customerName("Luis Felipe Cliente")
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity item(ProductionOrderEntity order, int quantity) {
        ProductEntity product = productRepository.save(ProductEntity.builder()
                .code("CONC-" + order.getCode())
                .name("Producto concurrente")
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
        return itemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .warehouseReceivedQty(0)
                .unitPrice(BigDecimal.valueOf(100))
                .build());
    }
}
