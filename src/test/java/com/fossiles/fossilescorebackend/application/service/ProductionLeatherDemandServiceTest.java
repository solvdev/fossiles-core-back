package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionLeatherDemandResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionLeatherDemandServiceTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private LeatherInventoryRepository leatherInventoryRepository;
    @Mock private LeatherRequirementService leatherRequirementService;

    private ProductionLeatherDemandService service;

    private final ProductEntity billetera = ProductEntity.builder().id(1L).code("B-7").name("Billetera").build();
    private final ProductEntity llavero = ProductEntity.builder().id(2L).code("LL-1").name("Llavero").build();
    private final MaterialEntity negroLiso = MaterialEntity.builder().id(50L).sku("M-NL").name("CUERO NEGRO LISO").build();

    @BeforeEach
    void setUp() {
        service = new ProductionLeatherDemandService(productionOrderRepository, productionOrderItemRepository,
                taskItemRepository, productRepository, colorRepository, materialRepository,
                leatherInventoryRepository, leatherRequirementService);
    }

    @Test
    void sumsByColorAndOrderSplittingDeliveredAndPending() {
        ProductionOrderEntity op1 = order(10L, "OPK-1", LocalDate.of(2026, 11, 5));
        ProductionOrderEntity op2 = order(11L, "OPK-2", LocalDate.of(2026, 11, 1));
        ProductionOrderItemEntity a = item(100L, 10L, 1L, 3L, 10); // 10 billeteras negras
        ProductionOrderItemEntity b = item(101L, 11L, 1L, 3L, 4);  // 4 billeteras negras
        ProductionOrderItemEntity c = item(102L, 11L, 2L, 3L, 5);  // 5 llaveros sin receta

        when(productionOrderRepository.findByStatusIn(ProductionLeatherDemandService.ACTIVE_STATUSES))
                .thenReturn(List.of(op1, op2));
        when(productionOrderItemRepository.findByProductionOrderIdIn(anyCollection())).thenReturn(List.of(a, b, c));
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(billetera, llavero));
        when(colorRepository.findAllById(anyIterable()))
                .thenReturn(List.of(ColorEntity.builder().id(3L).name("NEGRO").build()));
        when(materialRepository.findAllById(anyIterable())).thenReturn(List.of(negroLiso));
        when(leatherInventoryRepository.findByMaterialIdIn(anyCollection())).thenReturn(List.of(
                LeatherInventoryEntity.builder().materialId(50L).quantityAvailable(new BigDecimal("5")).build()));
        when(leatherRequirementService.resolvePerUnit(eq(billetera), anyLong()))
                .thenReturn(LeatherRequirementService.LeatherNeed.ok(50L, new BigDecimal("0.5")));
        when(leatherRequirementService.resolvePerUnit(eq(llavero), anyLong()))
                .thenReturn(LeatherRequirementService.LeatherNeed.blocked("Sin receta"));
        // OPK-1: 6 piezas en tarea iniciada, 2 en tarea pendiente con cuero entregado, 2 sin tarea.
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection())).thenReturn(List.of(
                new Object[]{100L, 6, "IN_PROGRESS", false, false},
                new Object[]{100L, 2, "PENDING", true, false},
                new Object[]{101L, 4, "PENDING", false, false}));

        ProductionLeatherDemandResponse res = service.report();

        assertThat(res.getTotals().getOrders()).isEqualTo(2);
        assertThat(res.getTotals().getTotalFt2()).isEqualByComparingTo("7");   // 14 × 0.5
        assertThat(res.getTotals().getDeliveredFt2()).isEqualByComparingTo("4"); // 8 × 0.5
        assertThat(res.getTotals().getPendingFt2()).isEqualByComparingTo("3");   // (2 + 4) × 0.5
        assertThat(res.getTotals().getShortageFt2()).isEqualByComparingTo("0");
        assertThat(res.getTotals().getMissingRecipeUnits()).isEqualTo(5);

        assertThat(res.getByColor()).hasSize(1);
        ProductionLeatherDemandResponse.ColorDemand negro = res.getByColor().get(0);
        assertThat(negro.getColorName()).isEqualTo("NEGRO");
        assertThat(negro.getOrders()).isEqualTo(2);
        assertThat(negro.getPendingFt2()).isEqualByComparingTo("3");
        assertThat(negro.getAvailableFt2()).isEqualByComparingTo("5");
        assertThat(negro.getMaterials()).containsExactly("CUERO NEGRO LISO");

        // Ordenadas por fecha de entrega: OPK-2 (1-nov) primero.
        assertThat(res.getOrders()).extracting(ProductionLeatherDemandResponse.OrderDemand::getCode)
                .containsExactly("OPK-2", "OPK-1");
        ProductionLeatherDemandResponse.OrderDemand opk1 = res.getOrders().get(1);
        assertThat(opk1.getPendingUnits()).isEqualTo(2);
        assertThat(opk1.getPendingFt2()).isEqualByComparingTo("1");
        assertThat(res.getOrders().get(0).getMissingRecipeLines()).isEqualTo(1);
    }

    @Test
    void shortageWhenStockDoesNotCoverPending() {
        ProductionOrderEntity op = order(10L, "OPK-1", null);
        when(productionOrderRepository.findByStatusIn(any())).thenReturn(List.of(op));
        when(productionOrderItemRepository.findByProductionOrderIdIn(anyCollection()))
                .thenReturn(List.of(item(100L, 10L, 1L, 3L, 20)));
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(billetera));
        when(colorRepository.findAllById(anyIterable())).thenReturn(List.of());
        when(materialRepository.findAllById(anyIterable())).thenReturn(List.of(negroLiso));
        when(leatherInventoryRepository.findByMaterialIdIn(anyCollection())).thenReturn(List.of(
                LeatherInventoryEntity.builder().materialId(50L).quantityAvailable(new BigDecimal("4")).build()));
        when(leatherRequirementService.resolvePerUnit(eq(billetera), anyLong()))
                .thenReturn(LeatherRequirementService.LeatherNeed.ok(50L, new BigDecimal("0.5")));
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection())).thenReturn(List.of());

        ProductionLeatherDemandResponse res = service.report();

        assertThat(res.getByMaterial().get(0).getPendingFt2()).isEqualByComparingTo("10");
        assertThat(res.getByMaterial().get(0).getShortageFt2()).isEqualByComparingTo("6");
        assertThat(res.getTotals().getShortageFt2()).isEqualByComparingTo("6");
    }

    private static ProductionOrderEntity order(Long id, String code, LocalDate delivery) {
        return ProductionOrderEntity.builder().id(id).code(code).orderType("CLIENTE_KIOSKO")
                .status("IN_PROGRESS").deliveryDate(delivery).build();
    }

    private static ProductionOrderItemEntity item(Long id, Long orderId, Long productId, Long colorId, int qty) {
        return ProductionOrderItemEntity.builder().id(id).productionOrderId(orderId)
                .productId(productId).colorId(colorId).quantity(qty).build();
    }
}
