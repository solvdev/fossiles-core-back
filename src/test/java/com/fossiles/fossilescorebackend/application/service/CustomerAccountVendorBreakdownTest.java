package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.service.CustomerAccountService.VendorOrderBreakdown;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** El desglose en lote del vendedor LF debe coincidir con estimateVendorOrderTotal sin tocar la base de datos. */
class CustomerAccountVendorBreakdownTest {

    private ProductionOrderItemRepository itemRepository;
    private ProductRepository productRepository;
    private CustomerAccountService service;
    private final Map<Long, ProductEntity> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        itemRepository = mock(ProductionOrderItemRepository.class);
        productRepository = mock(ProductRepository.class);
        service = new CustomerAccountService(
                mock(CustomerAccountEntryRepository.class),
                mock(CustomerRepository.class),
                mock(ProductionOrderRepository.class),
                itemRepository,
                mock(ProductionOrderPartialReleaseRepository.class),
                mock(ProductionOrderPartialReleaseLineRepository.class),
                mock(ProductShipmentRepository.class),
                mock(ProductShipmentDetailRepository.class),
                productRepository,
                mock(UserRepository.class),
                mock(SecurityUtil.class),
                new ObjectMapper());

        catalog.put(1L, ProductEntity.builder().id(1L).code("BIL-01").name("Billetera")
                .salePrice(new BigDecimal("120.00")).sellerPrice(new BigDecimal("80.00")).build());
        catalog.put(2L, ProductEntity.builder().id(2L).code("CIN-01").name("Cincho")
                .salePrice(new BigDecimal("200.00")).build());
        catalog.put(3L, ProductEntity.builder().id(3L).code("SUM-CAJA").name("Caja")
                .discountedPrice(new BigDecimal("15.50")).build());
        catalog.forEach((id, product) -> when(productRepository.findById(id)).thenReturn(Optional.of(product)));
        when(productRepository.findById(99L)).thenReturn(Optional.empty());
    }

    private ProductionOrderEntity order(long id, String seller, String type, String observations) {
        return ProductionOrderEntity.builder()
                .id(id).code("OPV-" + id).sellerName(seller).orderType(type).observations(observations).build();
    }

    private ProductionOrderItemEntity item(long id, long orderId, Long productId, Integer qty,
                                           String unitPrice, String sizesData) {
        return ProductionOrderItemEntity.builder()
                .id(id).productionOrderId(orderId).productId(productId).quantity(qty)
                .unitPrice(unitPrice != null ? new BigDecimal(unitPrice) : null)
                .sizesData(sizesData)
                .build();
    }

    @Test
    void batchTotalsMatchEstimateVendorOrderTotalAndSplitItems() {
        ProductionOrderEntity lf = order(1L, "LUIS FELIPE", "OPV",
                "Nota\n__OPV_PACKING__:[{\"quantity\":2,\"unitPrice\":5.5},{\"quantity\":1,\"unitPrice\":3}]\n__OPV_SHIPPING__:25");
        ProductionOrderEntity cincho = order(2L, "Luis Felipe Garcia", "CINCHOS", null);
        List<ProductionOrderItemEntity> lfItems = List.of(
                item(10L, 1L, 1L, 3, "100.00", null),
                item(11L, 1L, 2L, 2, null, null),
                item(12L, 1L, 3L, 4, null, null),
                item(13L, 1L, 2L, 3, "10", "{\"42\": 1, \"46\": 2}"),
                item(14L, 1L, 99L, 5, null, null));
        List<ProductionOrderItemEntity> cinchoItems = List.of(
                item(20L, 2L, 1L, 1, null, null),
                item(21L, 2L, null, 2, "7.25", null));

        when(itemRepository.findByProductionOrderId(1L)).thenReturn(lfItems);
        when(itemRepository.findByProductionOrderId(2L)).thenReturn(cinchoItems);
        BigDecimal expectedLf = service.estimateVendorOrderTotal(lf);
        BigDecimal expectedCincho = service.estimateVendorOrderTotal(cincho);
        org.mockito.Mockito.clearInvocations(productRepository);

        List<ProductionOrderItemEntity> all = new java.util.ArrayList<>(lfItems);
        all.addAll(cinchoItems);
        Map<Long, VendorOrderBreakdown> result =
                service.estimateVendorOrderBreakdowns(List.of(lf, cincho), all, catalog);

        assertThat(result.get(1L).total()).isEqualByComparingTo(expectedLf);
        assertThat(result.get(2L).total()).isEqualByComparingTo(expectedCincho);
        assertThat(expectedLf).isGreaterThan(BigDecimal.ZERO);

        VendorOrderBreakdown lfBreakdown = result.get(1L);
        assertThat(lfBreakdown.shippingCost()).isEqualByComparingTo("25");
        assertThat(lfBreakdown.packingTotal()).isEqualByComparingTo("14.00");
        BigDecimal itemSum = lfBreakdown.subtotalByItemId().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lfBreakdown.itemsSubtotal()).isEqualByComparingTo(itemSum);
        assertThat(lfBreakdown.total())
                .isEqualByComparingTo(itemSum.add(new BigDecimal("14.00")).add(new BigDecimal("25")));
        // Ítem 11: precio vendedor no aplica (sin sellerPrice en cincho) -> salePrice 200 x 2.
        assertThat(lfBreakdown.subtotalByItemId().get(11L)).isEqualByComparingTo("400.00");
        // Ítem 10: precio de la OP. Ítem 12 (empaque): precio con descuento de catálogo.
        assertThat(lfBreakdown.subtotalByItemId().get(10L)).isEqualByComparingTo("300.00");
        assertThat(lfBreakdown.subtotalByItemId().get(12L)).isEqualByComparingTo("62.00");
        // Producto inexistente en el catálogo prefetcheado se valora en cero.
        assertThat(lfBreakdown.subtotalByItemId().get(14L)).isEqualByComparingTo("0");
    }

    @Test
    void batchDoesNotQueryRepositories() {
        ProductionOrderEntity lf = order(1L, "LUIS FELIPE", "OPV", null);
        service.estimateVendorOrderBreakdowns(
                List.of(lf), List.of(item(10L, 1L, 1L, 2, null, null)), catalog);

        verifyNoInteractions(productRepository, itemRepository);
    }

    @Test
    void ordersWithoutItemsOrMetadataTotalZero() {
        ProductionOrderEntity empty = order(5L, "LUIS FELIPE", "OPV", null);
        Map<Long, VendorOrderBreakdown> result = service.estimateVendorOrderBreakdowns(List.of(empty), List.of(), Map.of());

        assertThat(result.get(5L).total()).isEqualByComparingTo("0");
        assertThat(result.get(5L).subtotalByItemId()).isEmpty();
        assertThat(result.get(5L).shippingCost()).isEqualByComparingTo("0");
        verify(itemRepository, org.mockito.Mockito.never()).findByProductionOrderId(5L);
    }
}
