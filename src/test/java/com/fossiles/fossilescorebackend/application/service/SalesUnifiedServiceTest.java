package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.SalesDashboardResponse.UnifiedSaleRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesUnifiedServiceTest {

    private final LocalDate today = LocalDate.of(2026, 9, 20);

    private KioskSaleRepository kioskSaleRepository;
    private KioskSaleItemRepository kioskSaleItemRepository;
    private OnlineSaleRepository onlineSaleRepository;
    private OnlineSaleItemRepository onlineSaleItemRepository;
    private ProductionOrderRepository productionOrderRepository;
    private ProductionOrderItemRepository productionOrderItemRepository;
    private SalesUnifiedService service;

    @BeforeEach
    void setUp() {
        kioskSaleRepository = mock(KioskSaleRepository.class);
        kioskSaleItemRepository = mock(KioskSaleItemRepository.class);
        onlineSaleRepository = mock(OnlineSaleRepository.class);
        onlineSaleItemRepository = mock(OnlineSaleItemRepository.class);
        productionOrderRepository = mock(ProductionOrderRepository.class);
        productionOrderItemRepository = mock(ProductionOrderItemRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        LocationRepository locationRepository = mock(LocationRepository.class);
        CustomerAccountService customerAccountService = new CustomerAccountService(
                mock(CustomerAccountEntryRepository.class),
                mock(CustomerRepository.class),
                productionOrderRepository,
                productionOrderItemRepository,
                mock(ProductionOrderPartialReleaseRepository.class),
                mock(ProductionOrderPartialReleaseLineRepository.class),
                mock(ProductShipmentRepository.class),
                mock(ProductShipmentDetailRepository.class),
                productRepository,
                mock(UserRepository.class),
                mock(SecurityUtil.class),
                new ObjectMapper());
        SalesSourceLoader loader = new SalesSourceLoader(kioskSaleRepository, onlineSaleRepository,
                productionOrderRepository, productRepository, customerAccountService);
        service = new SalesUnifiedService(loader, kioskSaleItemRepository, onlineSaleItemRepository,
                productionOrderItemRepository, locationRepository, customerAccountService);

        when(locationRepository.findAllById(anyCollection())).thenReturn(List.of(
                LocationEntity.builder().id(10L).code("K10").name("Kiosko Norte").build()));
        when(kioskSaleRepository.findHeaderRowsBySaleDateBetween(any(), any())).thenReturn(List.of(
                new KioskSaleHeaderRow(1L, "V-1", 10L, today, today.atTime(10, 0), "EFECTIVO", "COMPLETED",
                        new BigDecimal("3"), new BigDecimal("300.00"), false),
                new KioskSaleHeaderRow(2L, "V-2", 10L, today, today.atTime(11, 0), "EFECTIVO", "VOID",
                        BigDecimal.ONE, new BigDecimal("50.00"), false)));
        when(kioskSaleItemRepository.findRowsByKioskSaleIdIn(anyCollection())).thenReturn(List.of(
                new KioskSaleItemRow(1L, 1L, "CIN-01", "Cincho T.42", BigDecimal.ONE, new BigDecimal("100.00")),
                new KioskSaleItemRow(1L, 2L, "BIL-01", "Billetera", BigDecimal.ONE, new BigDecimal("100.00")),
                new KioskSaleItemRow(1L, 90L, "SUM-X", "Bolsa", BigDecimal.ONE, new BigDecimal("100.00"))));
        when(onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(any(), any())).thenReturn(List.of(
                OnlineSaleEntity.builder().id(5L).saleNumber("ON-5").saleDate(today.minusDays(1))
                        .totalAmount(new BigDecimal("80.00")).status("ENVIADO").salesperson("Ana").quantity(1)
                        .productName("Legacy").build()));
        when(onlineSaleItemRepository.findByOnlineSaleIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());
        when(productionOrderRepository.findVendorSalesDashboardOrders(any(), any())).thenReturn(List.of(
                ProductionOrderEntity.builder().id(9L).code("OPV-9").sellerName("LUIS FELIPE").orderType("OPV")
                        .startDate(today.minusDays(2)).status("PENDING").build()));
        when(productionOrderItemRepository.findByProductionOrderIdIn(anyCollection())).thenReturn(List.of(
                ProductionOrderItemEntity.builder().id(1L).productionOrderId(9L).productId(1L).quantity(2)
                        .unitPrice(new BigDecimal("50.00")).build()));
    }

    @Test
    void resolvesLabelsAndVendorTotalsInBatchWithoutPerRowQueries() throws Exception {
        List<UnifiedSaleRow> rows = service.getUnifiedSales(today.minusDays(7), today, null, null, 100);

        assertThat(rows).extracting(UnifiedSaleRow::getId).containsExactly("K-1", "O-5", "V-9");
        assertThat(rows.get(0).getProductName()).isEqualTo("Cincho T.42 +2 más");
        assertThat(rows.get(0).getKioskName()).isEqualTo("Kiosko Norte");
        assertThat(rows.get(0).getQuantity()).isEqualByComparingTo("3");
        assertThat(rows.get(1).getProductName()).isEqualTo("Legacy");
        assertThat(rows.get(2).getTotalAmount()).isEqualByComparingTo("100.00");
        assertThat(rows.get(2).getProductName()).isEqualTo("Orden OPV-9");

        verify(kioskSaleItemRepository, times(1)).findRowsByKioskSaleIdIn(anyCollection());
        verify(kioskSaleItemRepository, never()).findByKioskSaleIdOrderByIdAsc(anyLong());
        verify(onlineSaleItemRepository, times(1)).findByOnlineSaleIdInOrderByIdAsc(anyCollection());
        verify(onlineSaleItemRepository, never()).findByOnlineSaleIdOrderByIdAsc(anyLong());
        verify(productionOrderItemRepository, times(1)).findByProductionOrderIdIn(anyCollection());
        verify(productionOrderItemRepository, never()).findByProductionOrderId(anyLong());
        verify(productionOrderRepository, never()).findActiveOrders();
    }

    @Test
    void channelFilterAndLimitAreApplied() throws Exception {
        List<UnifiedSaleRow> online = service.getUnifiedSales(today.minusDays(7), today, "online", null, 100);
        assertThat(online).extracting(UnifiedSaleRow::getChannel).containsExactly("ONLINE");
        verify(kioskSaleRepository, never()).findHeaderRowsBySaleDateBetween(any(), any());

        List<UnifiedSaleRow> limited = service.getUnifiedSales(today.minusDays(7), today, null, null, 1);
        assertThat(limited).hasSize(1);
    }
}
