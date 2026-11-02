package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderLeatherEstimateResponse;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionOrderLeatherEstimateServiceTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private LeatherInventoryRepository leatherInventoryRepository;
    @Mock private LeatherRequirementService leatherRequirementService;

    private ProductionOrderLeatherEstimateService service;

    private final ProductEntity billetera = ProductEntity.builder().id(1L).code("B-7").name("Billetera").build();
    private final MaterialEntity negroLiso = MaterialEntity.builder().id(50L).sku("M-NL").name("CUERO NEGRO LISO").build();

    @BeforeEach
    void setUp() {
        service = new ProductionOrderLeatherEstimateService(
                productionOrderRepository, productionOrderItemRepository, taskItemRepository,
                productRepository, colorRepository, materialRepository,
                leatherInventoryRepository, leatherRequirementService);
    }

    @Test
    void noTasks_remainingEqualsTotal() throws Exception {
        stubSingleLine(10);
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection())).thenReturn(progress());

        ProductionOrderLeatherEstimateResponse res = service.estimate(10L);

        assertLine(res, 10, 10, 0, "5.000", "5.000", "0.000");
        assertThat(res.getTotalUnits()).isEqualTo(10);
        assertThat(res.getTotalQuantity()).isEqualTo(10);
        assertThat(res.getUsedQuantity()).isEqualTo(0);
        assertThat(res.getTotalFt2()).isEqualByComparingTo("5.000");
        assertThat(res.getUsedFt2()).isEqualByComparingTo("0.000");
        assertThat(res.getByColor().get(0).getQuantity()).isEqualTo(10);
        assertThat(res.getByColor().get(0).getTotalQuantity()).isEqualTo(10);
        assertThat(res.getByColor().get(0).getUsedQuantity()).isEqualTo(0);
        assertThat(res.getByColor().get(0).getTotalFt2()).isEqualByComparingTo("5.000");
        assertThat(res.getByMaterial().get(0).getTotalFt2()).isEqualByComparingTo("5.000");
        assertThat(res.getByMaterial().get(0).getUsedFt2()).isEqualByComparingTo("0.000");
        assertThat(res.getByMaterial().get(0).getAvailableFt2()).isEqualByComparingTo("2");
    }

    @Test
    void pendingTaskWithoutLeather_notSubtracted() throws Exception {
        stubSingleLine(10);
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection()))
                .thenReturn(progress(row(100L, 5, "PENDING", false, false)));

        ProductionOrderLeatherEstimateResponse res = service.estimate(10L);

        assertLine(res, 10, 10, 0, "5.000", "5.000", "0.000");
        assertThat(res.getTotalUnits()).isEqualTo(10);
        assertThat(res.getUsedQuantity()).isEqualTo(0);
    }

    @Test
    void pendingWithLeatherDeliveredOnTaskOrItem_subtracted() throws Exception {
        stubLines(
                item(100L, 10),
                item(101L, 10));
        when(leatherRequirementService.resolveNeed(eq(billetera), eq(3L), eq(10)))
                .thenReturn(LeatherRequirementService.LeatherNeed.ok(50L, new BigDecimal("5.000")));
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection()))
                .thenReturn(progress(
                        row(100L, 4, "PENDING", true, false),
                        row(101L, 3, "PENDING", false, true)));

        ProductionOrderLeatherEstimateResponse res = service.estimate(10L);

        ProductionOrderLeatherEstimateResponse.LineEstimate taskLeather = line(res, 100L);
        assertThat(taskLeather.getQuantity()).isEqualTo(6);
        assertThat(taskLeather.getUsedQuantity()).isEqualTo(4);
        assertThat(taskLeather.getTotalQuantity()).isEqualTo(10);
        assertThat(taskLeather.getLineFt2()).isEqualByComparingTo("3.000");
        assertThat(taskLeather.getUsedFt2()).isEqualByComparingTo("2.000");

        ProductionOrderLeatherEstimateResponse.LineEstimate itemLeather = line(res, 101L);
        assertThat(itemLeather.getQuantity()).isEqualTo(7);
        assertThat(itemLeather.getUsedQuantity()).isEqualTo(3);
        assertThat(itemLeather.getLineFt2()).isEqualByComparingTo("3.500");
        assertThat(itemLeather.getUsedFt2()).isEqualByComparingTo("1.500");

        assertThat(res.getTotalUnits()).isEqualTo(13);
        assertThat(res.getTotalQuantity()).isEqualTo(20);
        assertThat(res.getUsedQuantity()).isEqualTo(7);
        assertThat(res.getTotalFt2()).isEqualByComparingTo("6.500");
        assertThat(res.getUsedFt2()).isEqualByComparingTo("3.500");
    }

    @Test
    void inProgressOrCompletedTask_subtracted() throws Exception {
        stubSingleLine(10);
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection()))
                .thenReturn(progress(
                        row(100L, 4, "IN_PROGRESS", false, false),
                        row(100L, 3, "COMPLETED", false, false)));

        ProductionOrderLeatherEstimateResponse res = service.estimate(10L);

        assertLine(res, 3, 10, 7, "1.500", "5.000", "3.500");
        assertThat(res.getTotalUnits()).isEqualTo(3);
        assertThat(res.getTotalQuantity()).isEqualTo(10);
        assertThat(res.getUsedQuantity()).isEqualTo(7);
        assertThat(res.getByColor().get(0).getQuantity()).isEqualTo(3);
        assertThat(res.getByColor().get(0).getUsedQuantity()).isEqualTo(7);
        assertThat(res.getByColor().get(0).getTotalFt2()).isEqualByComparingTo("1.500");
        assertThat(res.getByColor().get(0).getUsedFt2()).isEqualByComparingTo("3.500");
        assertThat(res.getByMaterial().get(0).getTotalFt2()).isEqualByComparingTo("1.500");
        assertThat(res.getByMaterial().get(0).getUsedFt2()).isEqualByComparingTo("3.500");
    }

    @Test
    void usedAboveLineQuantity_cappedSoRemainingIsZeroNeverNegative() throws Exception {
        stubSingleLine(10);
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection()))
                .thenReturn(progress(
                        row(100L, 6, "IN_PROGRESS", false, false),
                        row(100L, 6, "COMPLETED", false, false)));

        ProductionOrderLeatherEstimateResponse res = service.estimate(10L);

        assertLine(res, 0, 10, 10, "0.000", "5.000", "5.000");
        assertThat(res.getLines().get(0).getQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(res.getLines().get(0).getLineFt2()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(res.getTotalUnits()).isEqualTo(0);
        assertThat(res.getTotalQuantity()).isEqualTo(10);
        assertThat(res.getUsedQuantity()).isEqualTo(10);
        assertThat(res.getTotalFt2()).isEqualByComparingTo("0.000");
        assertThat(res.getUsedFt2()).isEqualByComparingTo("5.000");
        assertThat(res.getByMaterial().get(0).getTotalFt2()).isEqualByComparingTo("0.000");
        BigDecimal remaining = res.getByMaterial().get(0).getTotalFt2();
        BigDecimal available = res.getByMaterial().get(0).getAvailableFt2();
        assertThat(remaining.subtract(available).max(BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void cancelledTask_ignored() throws Exception {
        stubSingleLine(10);
        when(taskItemRepository.findLeatherProgressRowsByItemIds(anyCollection()))
                .thenReturn(progress(row(100L, 8, "CANCELLED", true, true)));

        ProductionOrderLeatherEstimateResponse res = service.estimate(10L);

        assertLine(res, 10, 10, 0, "5.000", "5.000", "0.000");
        assertThat(res.getTotalUnits()).isEqualTo(10);
        assertThat(res.getUsedQuantity()).isEqualTo(0);
    }

    private void stubSingleLine(int qty) {
        stubLines(item(100L, qty));
        when(leatherRequirementService.resolveNeed(eq(billetera), eq(3L), eq(qty)))
                .thenReturn(LeatherRequirementService.LeatherNeed.ok(50L, new BigDecimal("5.000")));
    }

    private void stubLines(ProductionOrderItemEntity... items) {
        ProductionOrderEntity order = ProductionOrderEntity.builder()
                .id(10L).code("OPK-1").orderType("CLIENTE_KIOSKO").status("IN_PROGRESS").build();
        when(productionOrderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(productionOrderItemRepository.findByProductionOrderId(10L)).thenReturn(List.of(items));
        when(productRepository.findAllById(anyIterable())).thenReturn(List.of(billetera));
        when(colorRepository.findAllById(anyIterable()))
                .thenReturn(List.of(ColorEntity.builder().id(3L).name("NEGRO").build()));
        when(materialRepository.findById(50L)).thenReturn(Optional.of(negroLiso));
        when(leatherInventoryRepository.findByMaterialId(50L)).thenReturn(Optional.of(
                LeatherInventoryEntity.builder().materialId(50L).quantityAvailable(new BigDecimal("2")).build()));
    }

    private static ProductionOrderItemEntity item(Long id, int qty) {
        return ProductionOrderItemEntity.builder()
                .id(id).productionOrderId(10L).productId(1L).colorId(3L).quantity(qty).build();
    }

    private static Object[] row(Long itemId, int qty, String status, boolean taskLeather, boolean itemLeather) {
        return new Object[]{itemId, qty, status, taskLeather, itemLeather};
    }

    private static List<Object[]> progress(Object[]... rows) {
        return Arrays.asList(rows);
    }

    private static ProductionOrderLeatherEstimateResponse.LineEstimate line(
            ProductionOrderLeatherEstimateResponse res, Long itemId) {
        return res.getLines().stream().filter(l -> itemId.equals(l.getItemId())).findFirst().orElseThrow();
    }

    private static void assertLine(ProductionOrderLeatherEstimateResponse res,
                                   int remaining, int total, int used,
                                   String remainingFt2, String totalFt2, String usedFt2) {
        ProductionOrderLeatherEstimateResponse.LineEstimate line = res.getLines().get(0);
        assertThat(line.getQuantity()).isEqualTo(remaining);
        assertThat(line.getTotalQuantity()).isEqualTo(total);
        assertThat(line.getUsedQuantity()).isEqualTo(used);
        assertThat(line.getLineFt2()).isEqualByComparingTo(remainingFt2);
        assertThat(line.getTotalFt2()).isEqualByComparingTo(totalFt2);
        assertThat(line.getUsedFt2()).isEqualByComparingTo(usedFt2);
        assertThat(line.getFt2PerUnit()).isEqualByComparingTo("0.5000");
    }
}
