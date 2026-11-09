package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.Box;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.BoxLineRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.DispatchRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.DispatchResult;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.LineRow;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.OrderDetail;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.OrderSummary;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseLineRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseListResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.LfPackingConflictException;
import com.fossiles.fossilescorebackend.application.exception.LfPackingForbiddenException;
import com.fossiles.fossilescorebackend.application.exception.LfPackingUnauthenticatedException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LfPackingBoxEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderPartialReleaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LfPackingBoxRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderPartialReleaseRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Cajas de Bodega PT para OPV y OPC de Luis Felipe, sobre H2 en memoria (cada prueba hace rollback).
 * Cubre el tope de piezas, el despacho con piezas no recibidas, el despacho completo (un solo envío ENVP / OPC-ENV
 * en SENT), los permisos y que el pendiente del ERP reste las cajas.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:lf_packing_service;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1"
})
class LfPackingServiceTest {

    @Autowired
    private LfPackingService packing;
    @Autowired
    private ProductionOrderPartialReleaseService partialReleases;
    @Autowired
    private ProductionOrderRepository orderRepository;
    @Autowired
    private ProductionOrderItemRepository itemRepository;
    @Autowired
    private ProductionOrderWarehouseUnitRepository unitRepository;
    @Autowired
    private ProductionOrderPartialReleaseRepository releaseRepository;
    @Autowired
    private ProductShipmentRepository shipmentRepository;
    @Autowired
    private LfPackingBoxRepository boxRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private ShipmentNumberCounterRepository counters;

    @MockitoBean
    private SecurityUtil securityUtil;

    private ColorEntity color;
    private int seq = 0;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(9L);
        asRole("Bodega");
        color = colorRepository.save(ColorEntity.builder().name("NEGRO-LFP").build());
        // Correlativos que crea scripts/shipment-number-sequence.sql en producción.
        counters.save(ShipmentNumberCounterEntity.builder().type("LF_ENVP").lastValue(100L).build());
        counters.save(ShipmentNumberCounterEntity.builder().type("LF_OPC").lastValue(500L).build());
    }

    @Test
    @DisplayName("El tope por línea: lo que cabe en cajas es pedido − liberado − otras cajas, y el 409 dice cuánto queda")
    void boxLinesNeverExceedWhatIsAvailable() throws Exception {
        ProductionOrderEntity order = opv("OPV-LFP-01");
        ProductionOrderItemEntity item = item(order, "LFP-A", 10, null);

        Long box1 = createBox(order);
        Long box2 = createBox(order);
        packing.changeLine(order.getId(), box1, line(item, null, 7, null));

        assertThatThrownBy(() -> packing.changeLine(order.getId(), box2, line(item, null, 4, null)))
                .isInstanceOfSatisfying(LfPackingConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("QTY_UNAVAILABLE");
                    assertThat(ex.getMessage()).contains("Solo quedan 3");
                });

        OrderDetail detail = packing.changeLine(order.getId(), box2, line(item, null, 3, null));
        LineRow row = detail.getLines().get(0);
        assertThat(row.getOrdered()).isEqualTo(10);
        assertThat(row.getInBoxes()).isEqualTo(10);
        assertThat(row.getAvailableToPack()).isZero();

        // delta: dos escaneos seguidos suman; el tercero ya no cabe.
        assertThatThrownBy(() -> packing.changeLine(order.getId(), box2, line(item, null, null, 1)))
                .isInstanceOf(LfPackingConflictException.class);
        // Bajar una caja libera piezas para la otra.
        packing.changeLine(order.getId(), box1, line(item, null, 5, null));
        packing.changeLine(order.getId(), box2, line(item, null, null, 2));
        assertThat(boxRepository.findByProductionOrderIdOrderByBoxNoAsc(order.getId())).hasSize(2);
    }

    @Test
    @DisplayName("Armar cajas no mueve nada: ni envíos, ni liberaciones, ni numeración")
    void packingDoesNotCreateShipmentsOrReleases() throws Exception {
        ProductionOrderEntity order = opv("OPV-LFP-02");
        ProductionOrderItemEntity item = item(order, "LFP-B", 6, null);
        Long box = createBox(order);
        packing.changeLine(order.getId(), box, line(item, null, 6, null));
        packing.closeBox(order.getId(), box);

        assertThat(shipmentRepository.findByProductionOrderId(order.getId())).isEmpty();
        assertThat(releaseRepository.findByProductionOrderIdOrderBySequenceNumAsc(order.getId())).isEmpty();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getVendorShipmentNumber()).isNull();
    }

    @Test
    @DisplayName("Una caja cerrada no se edita hasta reabrirla; una vacía no se cierra")
    void closeAndReopen() throws Exception {
        ProductionOrderEntity order = opv("OPV-LFP-03");
        ProductionOrderItemEntity item = item(order, "LFP-C", 6, null);
        Long box = createBox(order);

        assertThatThrownBy(() -> packing.closeBox(order.getId(), box))
                .isInstanceOfSatisfying(LfPackingConflictException.class, ex -> assertThat(ex.getCode()).isEqualTo("BOX_EMPTY"));
        packing.changeLine(order.getId(), box, line(item, null, 2, null));
        packing.closeBox(order.getId(), box);
        assertThatThrownBy(() -> packing.changeLine(order.getId(), box, line(item, null, 3, null)))
                .isInstanceOfSatisfying(LfPackingConflictException.class, ex -> assertThat(ex.getCode()).isEqualTo("BOX_NOT_OPEN"));

        packing.reopenBox(order.getId(), box);
        OrderDetail detail = packing.changeLine(order.getId(), box, line(item, null, 3, null));
        assertThat(detail.getBoxes().get(0).getStatus()).isEqualTo("OPEN");
        assertThat(detail.getBoxes().get(0).getTotalPieces()).isEqualTo(3);
    }

    @Test
    @DisplayName("Despachar con piezas sin recibir: 409 con el detalle y no queda envío, liberación ni cajas despachadas")
    void dispatchWithUnreceivedPiecesIsRejected() throws Exception {
        ProductionOrderEntity order = opv("OPV-LFP-04");
        ProductionOrderItemEntity item = item(order, "LFP-D", 10, null);
        units(order, item, "", 10, 4); // solo 4 recibidas
        Long box = createBox(order);
        packing.changeLine(order.getId(), box, line(item, null, 6, null));
        packing.closeBox(order.getId(), box);

        // Armar sí se permite con piezas sin recibir: el detalle avisa cuántas faltan.
        Box shown = packing.getOrder(order.getId()).getBoxes().get(0);
        assertThat(shown.isAllReceived()).isFalse();
        assertThat(shown.getLines().get(0).getMissingReceipt()).isEqualTo(2);

        assertThatThrownBy(() -> packing.dispatch(order.getId(), DispatchRequest.builder().boxIds(List.of(box)).build()))
                .isInstanceOfSatisfying(LfPackingConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("PIECES_NOT_RECEIVED");
                    assertThat(ex.getDetails()).hasSize(1);
                    assertThat(ex.getDetails().get(0)).contains("LFP-D").contains("recibidas en reserva 4").contains("faltan 2");
                });
        assertThat(shipmentRepository.findByProductionOrderId(order.getId())).isEmpty();
        assertThat(releaseRepository.findByProductionOrderIdOrderBySequenceNumAsc(order.getId())).isEmpty();
        assertThat(boxRepository.findById(box).orElseThrow().getStatus()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("OPV: varias cajas se despachan en un solo ENVP en SENT, ligado a la liberación y a las cajas")
    void dispatchOpvCreatesOneSentShipmentForAllBoxes() throws Exception {
        ProductionOrderEntity order = opv("OPV-LFP-05");
        ProductionOrderItemEntity itemA = item(order, "LFP-E1", 10, null);
        ProductionOrderItemEntity itemB = item(order, "LFP-E2", 4, null);
        units(order, itemA, "", 10, 10);
        units(order, itemB, "", 4, 4);

        Long box1 = createBox(order);
        Long box2 = createBox(order);
        Long box3 = createBox(order);
        packing.changeLine(order.getId(), box1, line(itemA, null, 6, null));
        packing.changeLine(order.getId(), box1, line(itemB, null, 2, null));
        packing.changeLine(order.getId(), box2, line(itemA, null, 4, null));
        packing.changeLine(order.getId(), box3, line(itemB, null, 1, null)); // se queda abierta
        packing.closeBox(order.getId(), box1);
        packing.closeBox(order.getId(), box2);

        // Una caja abierta no se despacha.
        assertThatThrownBy(() -> packing.dispatch(order.getId(), DispatchRequest.builder().boxIds(List.of(box1, box3)).build()))
                .isInstanceOfSatisfying(LfPackingConflictException.class, ex -> assertThat(ex.getCode()).isEqualTo("BOX_NOT_CLOSED"));

        DispatchResult result = packing.dispatch(order.getId(), DispatchRequest.builder().boxIds(List.of(box1, box2)).build());

        assertThat(result.getShipmentNumber()).startsWith("ENVP-");
        assertThat(result.getShipmentStatus()).isEqualTo("SENT");
        assertThat(result.getTotalPieces()).isEqualTo(12);
        assertThat(shipmentRepository.findByProductionOrderId(order.getId())).hasSize(1);
        ProductShipmentEntity shipment = shipmentRepository.findByProductionOrderId(order.getId()).get(0);
        assertThat(shipment.getStatus()).isEqualTo("SENT");
        assertThat(shipment.getPartialReleaseId()).isEqualTo(result.getPartialReleaseId());
        ProductionOrderPartialReleaseEntity release = releaseRepository.findById(result.getPartialReleaseId()).orElseThrow();
        assertThat(release.getStatus()).isEqualTo("SHIPPED");

        List<LfPackingBoxEntity> boxes = boxRepository.findByProductionOrderIdOrderByBoxNoAsc(order.getId());
        assertThat(boxes).extracting(LfPackingBoxEntity::getStatus).containsExactly("DISPATCHED", "DISPATCHED", "OPEN");
        assertThat(boxes.get(0).getShipmentId()).isEqualTo(shipment.getId());
        assertThat(boxes.get(1).getShipmentId()).isEqualTo(shipment.getId());
        assertThat(boxes.get(0).getPartialReleaseId()).isEqualTo(release.getId());
        assertThat(boxes.get(2).getShipmentId()).isNull();

        // Las piezas despachadas salen de la reserva (quedan marcadas como enviadas) y las cuentas cuadran.
        long shipped = unitRepository.findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(order.getId())
                .stream().filter(u -> u.getShippedAt() != null).count();
        assertThat(shipped).isEqualTo(12);
        OrderDetail detail = result.getDetail();
        LineRow a = detail.getLines().stream().filter(l -> l.getProductionOrderItemId().equals(itemA.getId())).findFirst().orElseThrow();
        assertThat(a.getDispatched()).isEqualTo(10);
        assertThat(a.getInBoxes()).isZero();
        assertThat(detail.getBoxes().get(0).getShipmentNumber()).isEqualTo(result.getShipmentNumber());

        // Despachar otra vez la misma caja no repite nada.
        assertThatThrownBy(() -> packing.dispatch(order.getId(), DispatchRequest.builder().boxIds(List.of(box1)).build()))
                .isInstanceOfSatisfying(LfPackingConflictException.class, ex -> assertThat(ex.getCode()).isEqualTo("BOX_DISPATCHED"));
        assertThat(shipmentRepository.findByProductionOrderId(order.getId())).hasSize(1);
    }

    @Test
    @DisplayName("OPC con tallas: el envío sale como {OPC}-ENV-n en SENT con las tallas de las cajas")
    void dispatchOpcWithSizes() throws Exception {
        ProductionOrderEntity order = opc("OPC-LFP-06");
        ProductionOrderItemEntity item = item(order, "LFP-CINCHO", 10, "{\"38\":4,\"40\":6}");
        units(order, item, "38", 4, 4);
        units(order, item, "40", 6, 6);

        Long box1 = createBox(order);
        Long box2 = createBox(order);
        packing.changeLine(order.getId(), box1, line(item, "38", 3, null));
        packing.changeLine(order.getId(), box1, line(item, "40", 2, null));
        packing.changeLine(order.getId(), box2, line(item, "40", 4, null));
        assertThatThrownBy(() -> packing.changeLine(order.getId(), box2, line(item, "38", 2, null)))
                .isInstanceOfSatisfying(LfPackingConflictException.class, ex -> assertThat(ex.getMessage()).contains("Solo quedan 1"));
        assertThatThrownBy(() -> packing.changeLine(order.getId(), box2, line(item, "99", 1, null)))
                .isInstanceOf(BusinessException.class);
        packing.closeBox(order.getId(), box1);
        packing.closeBox(order.getId(), box2);

        DispatchResult result = packing.dispatch(order.getId(), DispatchRequest.builder().boxIds(List.of(box2, box1)).build());

        assertThat(result.getShipmentNumber()).contains("OPC-LFP-06-ENV-");
        assertThat(result.getShipmentStatus()).isEqualTo("SENT");
        assertThat(result.getTotalPieces()).isEqualTo(9);
        assertThat(shipmentRepository.findByProductionOrderId(order.getId())).hasSize(1);
        assertThat(result.getDetail().getLines()).extracting(LineRow::getDispatched).containsExactly(3, 6);
    }

    @Test
    @DisplayName("El pendiente del panel de liberación parcial del ERP resta las cajas y no deja liberar lo que está en cajas")
    void erpPendingSubtractsBoxes() throws Exception {
        ProductionOrderEntity order = opv("OPV-LFP-07");
        ProductionOrderItemEntity item = item(order, "LFP-G", 10, null);
        Long box = createBox(order);
        packing.changeLine(order.getId(), box, line(item, null, 7, null));

        PartialReleaseListResponse list = partialReleases.listForOrder(order.getId());
        assertThat(list.getOpenBoxPieces()).isEqualTo(7);
        assertThat(list.getOrderItemAvailability().get(0).getPendingTotal()).isEqualTo(3);
        assertThat(list.getOrderItemAvailability().get(0).getInOpenBoxes()).isEqualTo(7);

        assertThatThrownBy(() -> partialReleases.createDraft(order.getId(), PartialReleaseUpsertRequest.builder()
                .status("CONFIRMED")
                .lines(List.of(PartialReleaseLineRequest.builder().productionOrderItemId(item.getId()).quantity(5).build()))
                .build())).isInstanceOf(BusinessException.class).hasMessageContaining("pendiente");

        // Al borrar la caja las piezas vuelven al pendiente.
        packing.deleteBox(order.getId(), box);
        assertThat(partialReleases.listForOrder(order.getId()).getOrderItemAvailability().get(0).getPendingTotal()).isEqualTo(10);
    }

    @Test
    @DisplayName("Solo Bodega y Administración; sin sesión responde 401, con otro rol 403, y no toca nada")
    void onlyWarehouseAndAdminCanUseBoxes() throws Exception {
        ProductionOrderEntity order = opv("OPV-LFP-08");
        item(order, "LFP-H", 4, null);

        asRole("Produccion");
        assertThatThrownBy(() -> packing.createBox(order.getId(), null)).isInstanceOf(LfPackingForbiddenException.class);
        assertThatThrownBy(() -> packing.listOrders(null)).isInstanceOf(LfPackingForbiddenException.class);

        when(securityUtil.getCurrentUser()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> packing.getOrder(order.getId())).isInstanceOf(LfPackingUnauthenticatedException.class);
        assertThat(boxRepository.findByProductionOrderIdOrderByBoxNoAsc(order.getId())).isEmpty();

        asRole("Administracion");
        assertThat(packing.createBox(order.getId(), null).getBoxes()).hasSize(1);
    }

    @Test
    @DisplayName("La lista muestra OPV y OPC de LF con su estado, y se busca por orden o por número de envío")
    void listSearchesByOrderAndShipmentNumber() throws Exception {
        ProductionOrderEntity opv = opv("OPV-LFP-09");
        ProductionOrderItemEntity item = item(opv, "LFP-I", 5, null);
        ProductionOrderEntity opc = opc("OPC-LFP-09");
        item(opc, "LFP-J", 5, "{\"38\":5}");
        ProductionOrderEntity other = order("OPV-OTRO-09", "MARCAS", "Otro Vendedor");
        item(other, "LFP-K", 3, null);
        units(opv, item, "", 5, 5);
        Long box = createBox(opv);
        packing.changeLine(opv.getId(), box, line(item, null, 5, null));
        packing.closeBox(opv.getId(), box);

        List<OrderSummary> all = packing.listOrders("LFP-09");
        assertThat(all).extracting(OrderSummary::getCode).containsExactlyInAnyOrder("OPV-LFP-09", "OPC-LFP-09");
        OrderSummary opvRow = all.stream().filter(o -> o.getCode().equals("OPV-LFP-09")).findFirst().orElseThrow();
        assertThat(opvRow.getFamily()).isEqualTo("OPV");
        assertThat(opvRow.getWarehouseState()).isEqualTo("READY");
        assertThat(opvRow.getInBoxesUnits()).isEqualTo(5);
        assertThat(all.stream().filter(o -> o.getCode().equals("OPC-LFP-09")).findFirst().orElseThrow().getFamily()).isEqualTo("OPC");
        assertThat(packing.listOrders("OTRO")).isEmpty();

        DispatchResult result = packing.dispatch(opv.getId(), DispatchRequest.builder().boxIds(List.of(box)).build());
        List<OrderSummary> byShipment = packing.listOrders(result.getShipmentNumber());
        assertThat(byShipment).extracting(OrderSummary::getCode).containsExactly("OPV-LFP-09");
        assertThat(byShipment.get(0).getShipmentNumbers()).contains(result.getShipmentNumber());
        assertThat(byShipment.get(0).getWarehouseState()).isEqualTo("DISPATCHED_ALL");
    }

    // ───────────────────────────── Datos ─────────────────────────────

    private void asRole(String roleName) {
        UserEntity user = UserEntity.builder()
                .username("lfp-" + roleName)
                .roles(Set.of(RoleEntity.builder().name(roleName).build()))
                .build();
        when(securityUtil.getCurrentUser()).thenReturn(Optional.of(user));
    }

    private Long createBox(ProductionOrderEntity order) throws Exception {
        OrderDetail detail = packing.createBox(order.getId(), null);
        return detail.getBoxes().get(detail.getBoxes().size() - 1).getId();
    }

    private static BoxLineRequest line(ProductionOrderItemEntity item, String size, Integer quantity, Integer delta) {
        return BoxLineRequest.builder()
                .productionOrderItemId(item.getId())
                .sizeKey(size)
                .quantity(quantity)
                .delta(delta)
                .build();
    }

    private ProductionOrderEntity opv(String code) {
        return order(code, "MARCAS", "Luis Felipe");
    }

    private ProductionOrderEntity opc(String code) {
        return order(code, "CINCHOS", "Luis Felipe");
    }

    private ProductionOrderEntity order(String code, String orderType, String seller) {
        return orderRepository.save(ProductionOrderEntity.builder()
                .code(code)
                .orderType(orderType)
                .status("IN_PROGRESS")
                .sellerName(seller)
                .customerName("Luis Felipe Cliente")
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity item(ProductionOrderEntity order, String productCode, int quantity, String sizesData) {
        ProductEntity product = productRepository.save(ProductEntity.builder()
                .code(productCode + "-" + (++seq))
                .name("Producto " + productCode)
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
        return itemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .sizesData(sizesData)
                .warehouseReceivedQty(0)
                .unitPrice(BigDecimal.valueOf(100))
                .build());
    }

    /** {@code total} piezas de la línea/talla, de las cuales {@code received} ya están RECIBIDAS en reserva. */
    private void units(ProductionOrderEntity order, ProductionOrderItemEntity item, String size, int total, int received) {
        for (int i = 1; i <= total; i++) {
            unitRepository.save(ProductionOrderWarehouseUnitEntity.builder()
                    .productionOrderId(order.getId())
                    .productionOrderItemId(item.getId())
                    .unitLabel("#" + i)
                    .colorId(color.getId())
                    .sizeKey(size)
                    .unitSeq(i)
                    .receiptStatus(i <= received ? "RECEIVED" : "PENDING")
                    .receivedAt(i <= received ? LocalDateTime.now() : null)
                    .build());
        }
    }
}
