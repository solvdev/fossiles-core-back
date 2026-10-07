package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.SalesConsolidatedResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.BreakdownRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SourceKpis;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesDashboardServicesTest {

    private final LocalDate today = SalesDashboardSupport.today();
    private final DateRange range = new DateRange(today.minusDays(9), today);
    private final LocalDate previousDay = today.minusDays(15);
    private final LocalDate trendDay = today.minusMonths(3);

    private KioskSaleRepository kioskSaleRepository;
    private KioskSaleItemRepository kioskSaleItemRepository;
    private OnlineSaleRepository onlineSaleRepository;
    private OnlineSaleItemRepository onlineSaleItemRepository;
    private ProductionOrderRepository productionOrderRepository;
    private ProductionOrderItemRepository productionOrderItemRepository;
    private ProductRepository productRepository;
    private LocationRepository locationRepository;
    private CustomerRepository customerRepository;
    private SalesDashboardCache cache;
    private CustomerAccountService customerAccountService;

    private SalesSourceLoader loader;
    private KioskSalesDashboardService kioskService;
    private OnlineSalesDashboardService onlineService;
    private VendorSalesDashboardService vendorService;

    @BeforeEach
    void setUp() {
        kioskSaleRepository = mock(KioskSaleRepository.class);
        kioskSaleItemRepository = mock(KioskSaleItemRepository.class);
        onlineSaleRepository = mock(OnlineSaleRepository.class);
        onlineSaleItemRepository = mock(OnlineSaleItemRepository.class);
        productionOrderRepository = mock(ProductionOrderRepository.class);
        productionOrderItemRepository = mock(ProductionOrderItemRepository.class);
        productRepository = mock(ProductRepository.class);
        locationRepository = mock(LocationRepository.class);
        customerRepository = mock(CustomerRepository.class);
        cache = mock(SalesDashboardCache.class);
        customerAccountService = new CustomerAccountService(
                mock(CustomerAccountEntryRepository.class),
                customerRepository,
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

        loader = new SalesSourceLoader(kioskSaleRepository, onlineSaleRepository, productionOrderRepository,
                productRepository, customerAccountService);
        kioskService = new KioskSalesDashboardService(loader, kioskSaleItemRepository, locationRepository, cache);
        onlineService = new OnlineSalesDashboardService(loader, onlineSaleItemRepository, cache);
        vendorService = new VendorSalesDashboardService(
                loader, productionOrderItemRepository, customerRepository, customerAccountService, cache);

        when(locationRepository.findAllById(anyCollection())).thenReturn(List.of(
                LocationEntity.builder().id(10L).code("K10").name("Kiosko Norte").build(),
                LocationEntity.builder().id(11L).code("K11").name("Kiosko Sur").build()));
    }

    // ---------------------------------------------------------------- kioskos

    private KioskSaleHeaderRow kioskHeader(long id, long kioskId, LocalDate date, String total, String status, boolean test) {
        return new KioskSaleHeaderRow(id, "V-" + id, kioskId, date, date.atTime(12, 0), "EFECTIVO", status,
                BigDecimal.ONE, new BigDecimal(total), test);
    }

    private KioskSaleItemRow kioskItem(long saleId, Long productId, String code, String name, String qty, String line) {
        return new KioskSaleItemRow(saleId, productId, code, name, new BigDecimal(qty), new BigDecimal(line));
    }

    private void stubKioskData() {
        when(kioskSaleRepository.findHeaderRowsBySaleDateBetween(any(), any())).thenReturn(List.of(
                kioskHeader(1, 10, today, "300.00", "COMPLETED", false),
                kioskHeader(2, 11, today.minusDays(3), "100.00", "COMPLETED", false),
                kioskHeader(3, 10, previousDay, "200.00", "COMPLETED", false),
                kioskHeader(4, 10, trendDay, "500.00", "COMPLETED", false),
                kioskHeader(5, 10, today, "9999.00", "CANCELLED", false),
                kioskHeader(6, 10, today, "9999.00", "VOID", false),
                kioskHeader(7, 10, today, "9999.00", "COMPLETED", true)));
        when(kioskSaleItemRepository.findRowsBySaleDateBetween(any(), any())).thenReturn(List.of(
                kioskItem(1, 1L, "CIN-01", "Cincho T.42", "2", "250.00"),
                kioskItem(1, 90L, "SUM-BOLSA", "Bolsa", "1", "50.00"),
                kioskItem(2, 1L, "CIN-01", "Cincho T.44", "1", "100.00"),
                kioskItem(5, 1L, "CIN-01", "Cincho", "50", "9999.00")));
    }

    @Test
    void kioskPackagingCountsAsMoneyButNotUnitsNorRanking() {
        stubKioskData();

        SalesSourceDetailResponse result = kioskService.build(range, null);
        SourceKpis kpis = result.getKpis();

        assertThat(kpis.getTotalAmount()).isEqualByComparingTo("400.00");
        assertThat(kpis.getPackagingAmount()).isEqualByComparingTo("50.00");
        assertThat(kpis.getProductAmount()).isEqualByComparingTo("350.00");
        assertThat(kpis.getShippingAmount()).isEqualByComparingTo("0");
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("3");
        assertThat(result.getTopProducts()).hasSize(1);
        assertThat(result.getTopProducts().get(0).getProductName()).isEqualTo("Cincho");
        assertThat(result.getTopProducts().get(0).getUnits()).isEqualByComparingTo("3");
        assertThat(result.getTopProducts().get(0).getAmount()).isEqualByComparingTo("350.00");
        assertSplitAddsUp(kpis);
    }

    @Test
    void kioskExcludesCancelledVoidAndTestSales() {
        stubKioskData();

        SalesSourceDetailResponse result = kioskService.build(range, null);

        assertThat(result.getKpis().getSalesCount()).isEqualTo(2);
        assertThat(result.getKpis().getTotalAmount()).isEqualByComparingTo("400.00");
        assertThat(result.getRecentSales()).extracting(SalesSourceDetailResponse.SaleRow::getId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void kioskPreviousPeriodGrowthTodayAndLoadsOnce() {
        stubKioskData();

        SalesSourceDetailResponse result = kioskService.build(range, null);
        SourceKpis kpis = result.getKpis();

        assertThat(kpis.getPreviousTotalAmount()).isEqualByComparingTo("200.00");
        assertThat(kpis.getGrowthPercent()).isEqualByComparingTo("100.0");
        assertThat(kpis.getDailyAmount()).isEqualByComparingTo("300.00");
        assertThat(kpis.getAvgTicket()).isEqualByComparingTo("200.00");
        assertThat(result.getPreviousEndDate()).isEqualTo(range.from().minusDays(1));

        verify(kioskSaleRepository, times(1)).findHeaderRowsBySaleDateBetween(any(), any());
        verify(kioskSaleItemRepository, times(1)).findRowsBySaleDateBetween(any(), any());
        verify(kioskSaleItemRepository, never()).findByKioskSaleIdOrderByIdAsc(anyLong());
        verify(kioskSaleRepository, never()).findBySaleDateBetweenOrderBySoldAtDesc(any(), any());
        verify(productRepository, never()).findAll();
    }

    @Test
    void kioskLoadRangeCoversPreviousPeriodAndTrendStart() {
        stubKioskData();

        kioskService.build(range, null);

        LocalDate expectedFrom = SalesDashboardSupport.loadFrom(range);
        verify(kioskSaleRepository).findHeaderRowsBySaleDateBetween(expectedFrom, today);
        assertThat(expectedFrom).isBeforeOrEqualTo(YearMonth.from(today).minusMonths(5).atDay(1));
        assertThat(expectedFrom).isBeforeOrEqualTo(range.from().minusDays(10));
    }

    @Test
    void kioskDailySeriesIsZeroFilledAndTrendHasSixMonths() {
        stubKioskData();

        SalesSourceDetailResponse result = kioskService.build(range, null);

        assertThat(result.getDailySeries()).hasSize(10);
        assertThat(result.getDailySeries().get(0).getDate()).isEqualTo(range.from());
        assertThat(result.getDailySeries().get(9).getDate()).isEqualTo(today);
        assertThat(result.getDailySeries().get(9).getAmount()).isEqualByComparingTo("300.00");
        assertThat(result.getDailySeries().get(9).getCount()).isEqualTo(1);
        assertThat(result.getDailySeries().stream().filter(p -> p.getCount() == 0))
                .allSatisfy(p -> assertThat(p.getAmount()).isEqualByComparingTo("0"));
        assertThat(result.getDailySeries().stream().filter(p -> p.getCount() == 0).count()).isEqualTo(8);

        assertThat(result.getMonthlyTrend()).hasSize(6);
        assertThat(result.getMonthlyTrend().get(5).getYear()).isEqualTo(today.getYear());
        assertThat(result.getMonthlyTrend().get(5).getMonth()).isEqualTo(today.getMonthValue());
        assertThat(result.getMonthlyTrend().get(0).getLabel()).hasSize(3);
        BigDecimal trendSum = result.getMonthlyTrend().stream()
                .map(SalesSourceDetailResponse.TrendPoint::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(trendSum).isEqualByComparingTo("1100.00");
        YearMonth trendMonth = YearMonth.from(trendDay);
        assertThat(result.getMonthlyTrend().stream()
                .filter(p -> p.getYear() == trendMonth.getYear() && p.getMonth() == trendMonth.getMonthValue())
                .findFirst().orElseThrow().getAmount()).isGreaterThanOrEqualTo(new BigDecimal("500.00"));
    }

    @Test
    void kioskBreakdownsKioskFilterAndOptions() {
        stubKioskData();

        SalesSourceDetailResponse all = kioskService.build(range, null);
        assertThat(all.getBreakdowns().get("byKiosk")).extracting(BreakdownRow::getLabel)
                .containsExactly("Kiosko Norte", "Kiosko Sur");
        assertThat(all.getBreakdowns().get("byKiosk").get(0).getSharePercent()).isEqualByComparingTo("75.0");
        assertThat(all.getBreakdowns().get("byPaymentMethod")).hasSize(1);
        assertThat(all.getKioskOptions()).extracting(SalesSourceDetailResponse.KioskOption::getKioskId)
                .containsExactly(10L, 11L);

        SalesSourceDetailResponse filtered = kioskService.build(range, 11L);
        assertThat(filtered.getKpis().getTotalAmount()).isEqualByComparingTo("100.00");
        assertThat(filtered.getBreakdowns().get("byKiosk")).hasSize(1);
        assertThat(filtered.getKioskOptions()).hasSize(2);
        assertThat(filtered.getRecentSales().get(0).getProductLabel()).isEqualTo("Cincho");
    }

    @Test
    void kioskSaleWithOnlyPackagingIsLabelledAndHasNoUnits() {
        when(kioskSaleRepository.findHeaderRowsBySaleDateBetween(any(), any())).thenReturn(List.of(
                kioskHeader(1, 10, today, "50.00", "COMPLETED", false)));
        when(kioskSaleItemRepository.findRowsBySaleDateBetween(any(), any())).thenReturn(List.of(
                kioskItem(1, 90L, "SUM-BOLSA", "Bolsa", "1", "50.00")));

        SalesSourceDetailResponse result = kioskService.build(range, null);

        assertThat(result.getRecentSales().get(0).getProductLabel()).isEqualTo("Solo empaque");
        assertThat(result.getKpis().getUnitsFinished()).isEqualByComparingTo("0");
        assertThat(result.getKpis().getPackagingAmount()).isEqualByComparingTo("50.00");
        assertThat(result.getKpis().getProductAmount()).isEqualByComparingTo("0");
        assertThat(result.getTopProducts()).isEmpty();
    }

    @Test
    void kioskItemWithoutCodeIsClassifiedByCatalogCode() {
        when(kioskSaleRepository.findHeaderRowsBySaleDateBetween(any(), any())).thenReturn(List.of(
                kioskHeader(1, 10, today, "60.00", "COMPLETED", false)));
        when(kioskSaleItemRepository.findRowsBySaleDateBetween(any(), any())).thenReturn(List.of(
                kioskItem(1, 90L, null, "Bolsa", "1", "10.00"),
                kioskItem(1, 91L, null, "Billetera", "1", "50.00")));
        when(productRepository.findAllById(anyCollection())).thenReturn(List.of(
                ProductEntity.builder().id(90L).code("SUM-BOLSA").build(),
                ProductEntity.builder().id(91L).code("BIL-01").build()));

        SourceKpis kpis = kioskService.build(range, null).getKpis();

        assertThat(kpis.getPackagingAmount()).isEqualByComparingTo("10.00");
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("1");
    }

    // ----------------------------------------------------------------- online

    private OnlineSaleEntity onlineSale(long id, LocalDate date, String total, String shipping, String status) {
        return OnlineSaleEntity.builder().id(id).saleNumber("ON-" + id).saleDate(date)
                .totalAmount(new BigDecimal(total)).shippingCost(new BigDecimal(shipping)).status(status)
                .salesperson("Ana").socialNetwork("Instagram").paymentMethod("TARJETA_PAGADO").quantity(1)
                .build();
    }

    private OnlineSaleItemEntity onlineItem(long id, long saleId, Long productId, String code, String name, int qty, String subtotal) {
        return OnlineSaleItemEntity.builder().id(id).onlineSaleId(saleId).productId(productId).productCode(code)
                .productName(name).quantity(qty).subtotal(new BigDecimal(subtotal)).build();
    }

    private void stubOnlineData() {
        when(onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(any(), any())).thenReturn(List.of(
                onlineSale(1, today, "175.00", "25.00", "ENVIADO"),
                onlineSale(2, today.minusDays(2), "100.00", "0.00", "ENTREGADO"),
                onlineSale(3, previousDay, "50.00", "0.00", "ENTREGADO"),
                onlineSale(4, trendDay, "80.00", "0.00", "ENTREGADO"),
                onlineSale(5, today, "9999.00", "0.00", "CANCELADO"),
                onlineSale(6, today, "9999.00", "0.00", "anulada")));
        when(onlineSaleItemRepository.findBySaleDateBetween(any(), any())).thenReturn(List.of(
                onlineItem(1, 1, 1L, "BIL-01", "Billetera", 2, "100.00"),
                onlineItem(2, 1, 90L, "SUM-CAJA", "Caja", 1, "50.00"),
                onlineItem(3, 2, 1L, "BIL-01", "Billetera", 1, "100.00")));
    }

    @Test
    void onlinePackagingAndShippingSplitAddUpToTotal() {
        stubOnlineData();

        SalesSourceDetailResponse result = onlineService.build(range);
        SourceKpis kpis = result.getKpis();

        assertThat(kpis.getTotalAmount()).isEqualByComparingTo("275.00");
        assertThat(kpis.getShippingAmount()).isEqualByComparingTo("25.00");
        assertThat(kpis.getPackagingAmount()).isEqualByComparingTo("50.00");
        assertThat(kpis.getProductAmount()).isEqualByComparingTo("200.00");
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("3");
        assertSplitAddsUp(kpis);
        assertThat(result.getTopProducts()).hasSize(1);
        assertThat(result.getTopProducts().get(0).getProductCode()).isEqualTo("BIL-01");
        assertThat(result.getTopProducts().get(0).getUnits()).isEqualByComparingTo("3");
    }

    @Test
    void onlineExcludesCancelledAndComputesGrowthAndBreakdowns() {
        stubOnlineData();

        SalesSourceDetailResponse result = onlineService.build(range);

        assertThat(result.getKpis().getSalesCount()).isEqualTo(2);
        assertThat(result.getKpis().getPreviousTotalAmount()).isEqualByComparingTo("50.00");
        assertThat(result.getKpis().getGrowthPercent()).isEqualByComparingTo("450.0");
        assertThat(result.getKpis().getDailyAmount()).isEqualByComparingTo("175.00");
        assertThat(result.getBreakdowns()).containsOnlyKeys("bySeller", "bySocialNetwork", "byPaymentMethod", "byStatus");
        assertThat(result.getBreakdowns().get("byStatus")).extracting(BreakdownRow::getKey)
                .containsExactly("ENVIADO", "ENTREGADO");
        assertThat(result.getBreakdowns().get("bySeller").get(0).getSharePercent()).isEqualByComparingTo("100.0");
        assertThat(result.getKioskOptions()).isNull();
        assertThat(result.getRecentSales().get(0).getProductLabel()).isEqualTo("Billetera");
        verify(onlineSaleRepository, times(1)).findBySaleDateBetweenOrderBySaleDateDesc(any(), any());
        verify(onlineSaleItemRepository, times(1)).findBySaleDateBetween(any(), any());
        verify(onlineSaleItemRepository, never()).findByOnlineSaleIdOrderByIdAsc(anyLong());
    }

    @Test
    void onlineLegacySaleWithoutItemsUsesHeaderProduct() {
        OnlineSaleEntity legacy = onlineSale(1, today, "100.00", "10.00", "ENVIADO");
        legacy.setProductCode("SUM-CAJA");
        legacy.setProductName("Caja");
        when(onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(any(), any())).thenReturn(List.of(legacy));
        when(onlineSaleItemRepository.findBySaleDateBetween(any(), any())).thenReturn(List.of());

        SourceKpis kpis = onlineService.build(range).getKpis();

        assertThat(kpis.getPackagingAmount()).isEqualByComparingTo("90.00");
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("0");
        assertSplitAddsUp(kpis);
    }

    // --------------------------------------------------------------- vendedor

    private ProductionOrderEntity vendorOrder(long id, LocalDate date, String observations, String status) {
        return ProductionOrderEntity.builder().id(id).code("OPV-" + id).sellerName("LUIS FELIPE").orderType("OPV")
                .customerId(7L).customerName("Cliente Legacy").startDate(date).status(status)
                .observations(observations).build();
    }

    private ProductionOrderItemEntity vendorItem(long id, long orderId, Long productId, int qty, String unitPrice) {
        return ProductionOrderItemEntity.builder().id(id).productionOrderId(orderId).productId(productId)
                .quantity(qty).unitPrice(new BigDecimal(unitPrice)).build();
    }

    private void stubVendorData() {
        when(productionOrderRepository.findVendorSalesDashboardOrders(any(), any())).thenReturn(List.of(
                vendorOrder(1, today, "__OPV_PACKING__:[{\"quantity\":2,\"unitPrice\":10}]\n__OPV_SHIPPING__:30", "PENDING"),
                vendorOrder(2, previousDay, null, "COMPLETED"),
                vendorOrder(3, trendDay, null, "COMPLETED"),
                ProductionOrderEntity.builder().id(4L).code("INT-4").sellerName("LUIS FELIPE").orderType("INTERNA")
                        .startDate(today).status("PENDING").build(),
                voided(vendorOrder(5, today, null, "PENDING"))));
        when(productionOrderItemRepository.findByProductionOrderIdIn(anyCollection())).thenAnswer(inv -> {
            List<ProductionOrderItemEntity> items = new ArrayList<>(List.of(
                    vendorItem(10, 1, 1L, 4, "100.00"),
                    vendorItem(11, 1, 90L, 3, "20.00"),
                    vendorItem(12, 2, 1L, 1, "100.00"),
                    vendorItem(13, 3, 1L, 2, "100.00")));
            return items;
        });
        when(productRepository.findAllById(anyCollection())).thenReturn(List.of(
                ProductEntity.builder().id(1L).code("BIL-01").name("Billetera").build(),
                ProductEntity.builder().id(90L).code("SUM-CAJA").name("Caja").build()));
        when(customerRepository.findAllById(anyCollection())).thenReturn(List.of(
                CustomerEntity.builder().id(7L).name("Tienda Centro").build()));
    }

    private ProductionOrderEntity voided(ProductionOrderEntity order) {
        order.setVendorShipmentVoidedAt(LocalDateTime.now());
        return order;
    }

    @Test
    void vendorBreakdownSplitsPackagingShippingAndProduct() {
        stubVendorData();

        SalesSourceDetailResponse result = vendorService.build(range);
        SourceKpis kpis = result.getKpis();

        // Orden 1: 4 x 100 + 3 x 20 (empaque) + 2 x 10 (empaque en observaciones) + 30 envío.
        assertThat(kpis.getTotalAmount()).isEqualByComparingTo("510.00");
        assertThat(kpis.getProductAmount()).isEqualByComparingTo("400.00");
        assertThat(kpis.getPackagingAmount()).isEqualByComparingTo("80.00");
        assertThat(kpis.getShippingAmount()).isEqualByComparingTo("30.00");
        assertThat(kpis.getUnitsFinished()).isEqualByComparingTo("4");
        assertSplitAddsUp(kpis);
        assertThat(result.getTopProducts()).hasSize(1);
        assertThat(result.getTopProducts().get(0).getProductName()).isEqualTo("Billetera");
        assertThat(result.getTopProducts().get(0).getAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void vendorExcludesNonLfAndVoidedOrdersAndComparesPreviousPeriod() {
        stubVendorData();

        SalesSourceDetailResponse result = vendorService.build(range);

        assertThat(result.getKpis().getSalesCount()).isEqualTo(1);
        assertThat(result.getKpis().getPreviousTotalAmount()).isEqualByComparingTo("100.00");
        assertThat(result.getKpis().getGrowthPercent()).isEqualByComparingTo("410.0");
        assertThat(result.getKpis().getDailyAmount()).isEqualByComparingTo("510.00");
        assertThat(result.getBreakdowns()).containsOnlyKeys("byCustomer", "byOrderType", "byStatus");
        assertThat(result.getBreakdowns().get("byCustomer").get(0).getLabel()).isEqualTo("Tienda Centro");
        assertThat(result.getBreakdowns().get("byOrderType").get(0).getKey()).isEqualTo("OPV");
        assertThat(result.getBreakdowns().get("byOrderType").get(0).getLabel()).isEqualTo("OPV Fossiles");
        assertThat(result.getRecentSales().get(0).getProductLabel()).isEqualTo("Billetera");
        assertThat(result.getRecentSales().get(0).getParty()).isEqualTo("Tienda Centro");
        assertThat(result.getMonthlyTrend()).hasSize(6);
        BigDecimal trendSum = result.getMonthlyTrend().stream()
                .map(SalesSourceDetailResponse.TrendPoint::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(trendSum).isEqualByComparingTo("810.00");
        assertThat(result.getDailySeries()).hasSize(10);
    }

    @Test
    void vendorLoadsItemsProductsAndCustomersInBatches() {
        stubVendorData();

        vendorService.build(range);

        verify(productionOrderRepository, times(1)).findVendorSalesDashboardOrders(any(), any());
        verify(productionOrderItemRepository, times(1)).findByProductionOrderIdIn(anyCollection());
        verify(productionOrderItemRepository, never()).findByProductionOrderId(anyLong());
        verify(productRepository, times(1)).findAllById(anyCollection());
        verify(productRepository, never()).findById(anyLong());
        verify(productRepository, never()).findAll();
        verify(customerRepository, times(1)).findAllById(anyCollection());
        verify(productionOrderRepository, never()).findActiveOrders();
    }

    // ------------------------------------------------------------ consolidado

    @Test
    void consolidatedSumsSourcesAndKeepsMoneySplit() {
        stubKioskData();
        stubOnlineData();
        stubVendorData();
        SalesConsolidatedService consolidated =
                new SalesConsolidatedService(kioskService, onlineService, vendorService, cache);

        SalesConsolidatedResponse result = consolidated.build(
                range, kioskService.build(range, null), onlineService.build(range), vendorService.build(range));
        SourceKpis totals = result.getTotals();

        assertThat(totals.getTotalAmount()).isEqualByComparingTo("1185.00");
        assertThat(totals.getPackagingAmount()).isEqualByComparingTo("180.00");
        assertThat(totals.getShippingAmount()).isEqualByComparingTo("55.00");
        assertThat(totals.getUnitsFinished()).isEqualByComparingTo("10");
        assertThat(totals.getSalesCount()).isEqualTo(5);
        assertSplitAddsUp(totals);
        assertThat(totals.getPreviousTotalAmount()).isEqualByComparingTo("350.00");
        assertThat(totals.getGrowthPercent()).isEqualByComparingTo("238.6");

        assertThat(result.getSources()).extracting(SalesConsolidatedResponse.SourceSummary::getChannel)
                .containsExactly("KIOSKO", "ONLINE", "VENDOR");
        BigDecimal shares = result.getSources().stream()
                .map(SalesConsolidatedResponse.SourceSummary::getSharePercent).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(shares).isBetween(new BigDecimal("99.8"), new BigDecimal("100.2"));
        result.getSources().forEach(s -> assertSplitAddsUp(s.getKpis()));

        assertThat(result.getMonthlyTrend()).hasSize(6);
        result.getMonthlyTrend().forEach(p -> assertThat(p.getTotal())
                .isEqualByComparingTo(p.getKiosko().add(p.getOnline()).add(p.getVendor())));
        assertThat(result.getDailySeries()).hasSize(10);
        BigDecimal dailySum = result.getDailySeries().stream()
                .map(SalesConsolidatedResponse.DailySourcePoint::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dailySum).isEqualByComparingTo(totals.getTotalAmount());
    }

    // ----------------------------------------------------------------- común

    @Test
    void rejectsInvertedRange() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.fossiles.fossilescorebackend.application.exception.BusinessException.class,
                () -> kioskService.getDashboard(today, today.minusDays(1), null, false));
    }

    @Test
    void previousPeriodHasSameLengthRightBeforeStart() {
        DateRange previous = SalesDashboardSupport.previousPeriod(new DateRange(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 19)));

        assertThat(previous.from()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(previous.to()).isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    void growthWithoutPreviousSalesIsHundredOrZero() {
        assertThat(SalesDashboardSupport.growthPercent(new BigDecimal("10"), BigDecimal.ZERO)).isEqualByComparingTo("100");
        assertThat(SalesDashboardSupport.growthPercent(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(SalesDashboardSupport.growthPercent(new BigDecimal("50"), new BigDecimal("100"))).isEqualByComparingTo("-50.0");
    }

    private static void assertSplitAddsUp(SourceKpis kpis) {
        assertThat(kpis.getProductAmount().add(kpis.getPackagingAmount()).add(kpis.getShippingAmount()))
                .isEqualByComparingTo(kpis.getTotalAmount());
    }
}
