package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesDashboardResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SalesDashboardService {

    private static final String CHANNEL_KIOSKO = "KIOSKO";
    private static final String CHANNEL_ONLINE = "ONLINE";
    private static final String CHANNEL_VENDOR = "VENDOR";
    private static final int RECENT_SALES_LIMIT = 40;
    private static final int TOP_PRODUCTS_LIMIT = 5;
    private static final int MONTHLY_TREND_MONTHS = 6;

    private final KioskSaleRepository kioskSaleRepository;
    private final KioskSaleItemRepository kioskSaleItemRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final CustomerAccountService customerAccountService;

    @Transactional(readOnly = true)
    public SalesDashboardResponse getDashboard(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId,
            String scope
    ) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        LocalDate today = LocalDate.now();
        boolean kioskOnly = "kiosko".equalsIgnoreCase(safeText(scope));

        List<KioskSaleEntity> kioskSales = loadKioskSales(range.from(), range.to(), kioskLocationId);
        List<OnlineSaleEntity> onlineSales = kioskOnly ? List.of() : loadOnlineSales(range.from(), range.to());
        List<ProductionOrderEntity> vendorOrders = kioskOnly ? List.of() : loadVendorOrders(range.from(), range.to());

        List<KioskSaleEntity> kioskSalesToday = kioskSales.stream()
                .filter(s -> today.equals(s.getSaleDate()))
                .collect(Collectors.toList());
        List<OnlineSaleEntity> onlineSalesToday = onlineSales.stream()
                .filter(s -> today.equals(s.getSaleDate()))
                .collect(Collectors.toList());
        List<ProductionOrderEntity> vendorOrdersToday = vendorOrders.stream()
                .filter(o -> today.equals(resolveVendorOrderDate(o)))
                .collect(Collectors.toList());

        SalesDashboardResponse.ChannelSummary kiosko = buildKioskChannel(kioskSales, kioskSalesToday, range);
        SalesDashboardResponse.ChannelSummary online = kioskOnly
                ? emptyChannel(CHANNEL_ONLINE, "Online")
                : buildOnlineChannel(onlineSales, onlineSalesToday, range);
        SalesDashboardResponse.ChannelSummary vendor = kioskOnly
                ? emptyChannel(CHANNEL_VENDOR, "Vendedor LF")
                : buildVendorChannel(vendorOrders, vendorOrdersToday, range);

        BigDecimal periodTotal = kioskOnly
                ? safeAmount(kiosko.getTotalAmount())
                : sumAmounts(kiosko.getTotalAmount(), online.getTotalAmount(), vendor.getTotalAmount());
        BigDecimal dailyTotal = kioskOnly
                ? safeAmount(kiosko.getDailyAmount())
                : sumAmounts(kiosko.getDailyAmount(), online.getDailyAmount(), vendor.getDailyAmount());
        int salesCount = kioskOnly
                ? safeCount(kiosko.getSalesCount())
                : safeCount(kiosko.getSalesCount()) + safeCount(online.getSalesCount()) + safeCount(vendor.getSalesCount());

        DateRange previousRange = previousPeriod(range.from(), range.to());
        BigDecimal previousTotal;
        if (kioskOnly) {
            previousTotal = buildKioskChannel(
                    loadKioskSales(previousRange.from(), previousRange.to(), kioskLocationId),
                    List.of(),
                    previousRange
            ).getTotalAmount();
        } else {
            previousTotal = sumChannelTotals(
                    buildKioskChannel(loadKioskSales(previousRange.from(), previousRange.to(), kioskLocationId), List.of(), previousRange).getTotalAmount(),
                    buildOnlineChannel(loadOnlineSales(previousRange.from(), previousRange.to()), List.of(), previousRange).getTotalAmount(),
                    buildVendorChannel(loadVendorOrders(previousRange.from(), previousRange.to()), List.of(), previousRange).getTotalAmount()
            );
        }

        List<SalesDashboardResponse.KioskOption> kiosks = buildKioskOptions(
                loadKioskSales(range.from(), range.to(), null));

        return SalesDashboardResponse.builder()
                .startDate(range.from())
                .endDate(range.to())
                .kiosko(kiosko)
                .online(online)
                .vendor(vendor)
                .totals(SalesDashboardResponse.PeriodTotals.builder()
                        .totalAmount(periodTotal)
                        .dailyAmount(dailyTotal)
                        .growthPercent(growthPercent(periodTotal, previousTotal))
                        .salesCount(salesCount)
                        .build())
                .topProducts(buildTopProducts(kioskSales, onlineSales, vendorOrders))
                .monthlyTrend(buildMonthlyTrend(range.to(), kioskLocationId, kioskOnly))
                .kiosks(kiosks)
                .recentSales(buildUnifiedRows(kioskSales, onlineSales, vendorOrders, RECENT_SALES_LIMIT))
                .build();
    }

    @Transactional(readOnly = true)
    public List<SalesDashboardResponse.UnifiedSaleRow> getUnifiedSales(
            LocalDate startDate,
            LocalDate endDate,
            String channel,
            Long kioskLocationId,
            Integer limit
    ) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        String normalizedChannel = normalizeChannel(channel);
        int rowLimit = limit != null && limit > 0 ? Math.min(limit, 2000) : 500;

        List<KioskSaleEntity> kioskSales = normalizedChannel == null || CHANNEL_KIOSKO.equals(normalizedChannel)
                ? loadKioskSales(range.from(), range.to(), kioskLocationId)
                : List.of();
        List<OnlineSaleEntity> onlineSales = normalizedChannel == null || CHANNEL_ONLINE.equals(normalizedChannel)
                ? loadOnlineSales(range.from(), range.to())
                : List.of();
        List<ProductionOrderEntity> vendorOrders = normalizedChannel == null || CHANNEL_VENDOR.equals(normalizedChannel)
                ? loadVendorOrders(range.from(), range.to())
                : List.of();

        return buildUnifiedRows(kioskSales, onlineSales, vendorOrders, rowLimit);
    }

    private SalesDashboardResponse.ChannelSummary buildKioskChannel(
            List<KioskSaleEntity> periodSales,
            List<KioskSaleEntity> todaySales,
            DateRange range) {
        BigDecimal periodAmount = sumKioskAmount(periodSales);
        DateRange previousRange = previousPeriod(range.from(), range.to());
        BigDecimal previousAmount = sumKioskAmount(loadKioskSales(previousRange.from(), previousRange.to(), null));
        return SalesDashboardResponse.ChannelSummary.builder()
                .channel(CHANNEL_KIOSKO)
                .label("Kioskos")
                .salesCount(periodSales.size())
                .totalAmount(periodAmount)
                .dailyAmount(sumKioskAmount(todaySales))
                .growthPercent(growthPercent(periodAmount, previousAmount))
                .build();
    }

    private SalesDashboardResponse.ChannelSummary buildOnlineChannel(
            List<OnlineSaleEntity> periodSales,
            List<OnlineSaleEntity> todaySales,
            DateRange range) {
        BigDecimal periodAmount = sumOnlineAmount(periodSales);
        DateRange previousRange = previousPeriod(range.from(), range.to());
        BigDecimal previousAmount = sumOnlineAmount(loadOnlineSales(previousRange.from(), previousRange.to()));
        return SalesDashboardResponse.ChannelSummary.builder()
                .channel(CHANNEL_ONLINE)
                .label("Online")
                .salesCount(periodSales.size())
                .totalAmount(periodAmount)
                .dailyAmount(sumOnlineAmount(todaySales))
                .growthPercent(growthPercent(periodAmount, previousAmount))
                .build();
    }

    private SalesDashboardResponse.ChannelSummary buildVendorChannel(
            List<ProductionOrderEntity> periodOrders,
            List<ProductionOrderEntity> todayOrders,
            DateRange range) {
        BigDecimal periodAmount = sumVendorAmount(periodOrders);
        DateRange previousRange = previousPeriod(range.from(), range.to());
        BigDecimal previousAmount = sumVendorAmount(loadVendorOrders(previousRange.from(), previousRange.to()));
        return SalesDashboardResponse.ChannelSummary.builder()
                .channel(CHANNEL_VENDOR)
                .label("Vendedor LF")
                .salesCount(periodOrders.size())
                .totalAmount(periodAmount)
                .dailyAmount(sumVendorAmount(todayOrders))
                .growthPercent(growthPercent(periodAmount, previousAmount))
                .build();
    }

    private List<SalesDashboardResponse.KioskOption> buildKioskOptions(List<KioskSaleEntity> sales) {
        Set<Long> kioskIds = sales.stream()
                .map(KioskSaleEntity::getKioskLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LocationEntity> kioskMap = locationRepository.findAllById(kioskIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, row -> row));

        Map<Long, SalesDashboardResponse.KioskOption> grouped = new LinkedHashMap<>();
        for (KioskSaleEntity sale : sales) {
            Long kioskId = sale.getKioskLocationId();
            if (kioskId == null) {
                continue;
            }
            SalesDashboardResponse.KioskOption current = grouped.get(kioskId);
            if (current == null) {
                LocationEntity kiosk = kioskMap.get(kioskId);
                current = SalesDashboardResponse.KioskOption.builder()
                        .kioskId(kioskId)
                        .kioskCode(kiosk != null ? kiosk.getCode() : "")
                        .kioskName(kiosk != null ? kiosk.getName() : "Kiosko")
                        .salesCount(0)
                        .totalAmount(BigDecimal.ZERO)
                        .build();
            }
            current.setSalesCount(current.getSalesCount() + 1);
            current.setTotalAmount(current.getTotalAmount().add(safeAmount(sale.getTotalAmount())));
            grouped.put(kioskId, current);
        }

        return grouped.values().stream()
                .sorted(Comparator.comparing(SalesDashboardResponse.KioskOption::getKioskName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    private List<SalesDashboardResponse.TopProductSummary> buildTopProducts(
            List<KioskSaleEntity> kioskSales,
            List<OnlineSaleEntity> onlineSales,
            List<ProductionOrderEntity> vendorOrders) {
        Map<String, BigDecimal> unitsByProduct = new HashMap<>();

        for (KioskSaleEntity sale : kioskSales) {
            List<KioskSaleItemEntity> items = kioskSaleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());
            for (KioskSaleItemEntity item : items) {
                addUnits(unitsByProduct, item.getProductName(), item.getQuantity());
            }
        }

        for (OnlineSaleEntity sale : onlineSales) {
            List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
            if (items.isEmpty()) {
                addUnits(unitsByProduct, sale.getProductName(), toBigDecimal(sale.getQuantity()));
            } else {
                for (OnlineSaleItemEntity item : items) {
                    addUnits(unitsByProduct, item.getProductName(), toBigDecimal(item.getQuantity()));
                }
            }
        }

        Map<Long, String> productNames = productRepository.findAll().stream()
                .collect(Collectors.toMap(ProductEntity::getId, ProductEntity::getName, (a, b) -> a));
        for (ProductionOrderEntity order : vendorOrders) {
            List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(order.getId());
            for (ProductionOrderItemEntity item : items) {
                String name = item.getBrandName();
                if (name == null || name.isBlank()) {
                    name = productNames.getOrDefault(item.getProductId(), "Producto");
                }
                addUnits(unitsByProduct, name, toBigDecimal(resolveItemQuantity(item)));
            }
        }

        return unitsByProduct.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(TOP_PRODUCTS_LIMIT)
                .map(entry -> SalesDashboardResponse.TopProductSummary.builder()
                        .productName(entry.getKey())
                        .units(entry.getValue())
                        .build())
                .collect(Collectors.toList());
    }

    private SalesDashboardResponse.ChannelSummary emptyChannel(String channel, String label) {
        return SalesDashboardResponse.ChannelSummary.builder()
                .channel(channel)
                .label(label)
                .salesCount(0)
                .totalAmount(BigDecimal.ZERO)
                .dailyAmount(BigDecimal.ZERO)
                .growthPercent(BigDecimal.ZERO)
                .build();
    }

    private List<SalesDashboardResponse.MonthlyTrendPoint> buildMonthlyTrend(
            LocalDate anchorDate,
            Long kioskLocationId,
            boolean kioskOnly) {
        YearMonth endMonth = YearMonth.from(anchorDate != null ? anchorDate : LocalDate.now());
        List<SalesDashboardResponse.MonthlyTrendPoint> points = new ArrayList<>();

        for (int offset = MONTHLY_TREND_MONTHS - 1; offset >= 0; offset--) {
            YearMonth month = endMonth.minusMonths(offset);
            LocalDate from = month.atDay(1);
            LocalDate to = month.atEndOfMonth();

            BigDecimal kiosko = sumKioskAmount(loadKioskSales(from, to, kioskLocationId));
            BigDecimal online = kioskOnly ? BigDecimal.ZERO : sumOnlineAmount(loadOnlineSales(from, to));
            BigDecimal vendor = kioskOnly ? BigDecimal.ZERO : sumVendorAmount(loadVendorOrders(from, to));

            points.add(SalesDashboardResponse.MonthlyTrendPoint.builder()
                    .label(month.getMonth().getDisplayName(TextStyle.SHORT, new Locale("es", "GT")))
                    .year(month.getYear())
                    .month(month.getMonthValue())
                    .kiosko(kiosko)
                    .online(online)
                    .vendor(vendor)
                    .total(sumAmounts(kiosko, online, vendor))
                    .build());
        }

        return points;
    }

    private List<SalesDashboardResponse.UnifiedSaleRow> buildUnifiedRows(
            List<KioskSaleEntity> kioskSales,
            List<OnlineSaleEntity> onlineSales,
            List<ProductionOrderEntity> vendorOrders,
            int limit) {
        Map<Long, LocationEntity> kioskMap = locationRepository.findAllById(
                kioskSales.stream().map(KioskSaleEntity::getKioskLocationId).filter(Objects::nonNull).collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(LocationEntity::getId, row -> row));

        List<SalesDashboardResponse.UnifiedSaleRow> rows = new ArrayList<>();

        for (KioskSaleEntity sale : kioskSales) {
            LocationEntity kiosk = kioskMap.get(sale.getKioskLocationId());
            rows.add(SalesDashboardResponse.UnifiedSaleRow.builder()
                    .id("K-" + sale.getId())
                    .saleDate(sale.getSaleDate())
                    .channel(CHANNEL_KIOSKO)
                    .channelLabel("Kiosko")
                    .reference(sale.getSaleNumber())
                    .productName(resolveKioskProductLabel(sale.getId()))
                    .quantity(sale.getTotalItems())
                    .totalAmount(safeAmount(sale.getTotalAmount()))
                    .kioskName(kiosk != null ? kiosk.getName() : null)
                    .build());
        }

        for (OnlineSaleEntity sale : onlineSales) {
            rows.add(SalesDashboardResponse.UnifiedSaleRow.builder()
                    .id("O-" + sale.getId())
                    .saleDate(sale.getSaleDate())
                    .channel(CHANNEL_ONLINE)
                    .channelLabel("Online")
                    .reference(sale.getSaleNumber())
                    .productName(resolveOnlineProductLabel(sale))
                    .quantity(toBigDecimal(sale.getQuantity()))
                    .totalAmount(safeAmount(sale.getTotalAmount()))
                    .sellerName(sale.getSalesperson())
                    .build());
        }

        for (ProductionOrderEntity order : vendorOrders) {
            rows.add(SalesDashboardResponse.UnifiedSaleRow.builder()
                    .id("V-" + order.getId())
                    .saleDate(resolveVendorOrderDate(order))
                    .channel(CHANNEL_VENDOR)
                    .channelLabel("Vendedor LF")
                    .reference(order.getCode())
                    .productName("Orden " + order.getCode())
                    .quantity(BigDecimal.ONE)
                    .totalAmount(customerAccountService.estimateVendorOrderTotal(order))
                    .sellerName(order.getSellerName())
                    .build());
        }

        return rows.stream()
                .sorted(Comparator
                        .comparing(SalesDashboardResponse.UnifiedSaleRow::getSaleDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SalesDashboardResponse.UnifiedSaleRow::getId, Comparator.reverseOrder()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String resolveKioskProductLabel(Long saleId) {
        List<KioskSaleItemEntity> items = kioskSaleItemRepository.findByKioskSaleIdOrderByIdAsc(saleId);
        if (items.isEmpty()) {
            return "Venta kiosko";
        }
        if (items.size() == 1) {
            return items.get(0).getProductName();
        }
        return items.get(0).getProductName() + " +" + (items.size() - 1) + " más";
    }

    private String resolveOnlineProductLabel(OnlineSaleEntity sale) {
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        if (items.isEmpty()) {
            return sale.getProductName() != null ? sale.getProductName() : "Venta online";
        }
        if (items.size() == 1) {
            return items.get(0).getProductName();
        }
        return items.get(0).getProductName() + " +" + (items.size() - 1) + " más";
    }

    private List<KioskSaleEntity> loadKioskSales(LocalDate from, LocalDate to, Long kioskLocationId) {
        List<KioskSaleEntity> sales;
        if (kioskLocationId != null) {
            sales = kioskSaleRepository.findByKioskLocationIdAndSaleDateBetweenOrderBySoldAtDesc(kioskLocationId, from, to);
        } else {
            sales = kioskSaleRepository.findBySaleDateBetweenOrderBySoldAtDesc(from, to);
        }
        return sales.stream()
                .filter(s -> !"CANCELLED".equalsIgnoreCase(safeText(s.getStatus())))
                .filter(s -> !"VOID".equalsIgnoreCase(safeText(s.getStatus())))
                .filter(KioskPosService::countsForProductionMetrics)
                .collect(Collectors.toList());
    }

    private List<OnlineSaleEntity> loadOnlineSales(LocalDate from, LocalDate to) {
        return onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(from, to).stream()
                .filter(s -> !isCancelledOnlineSale(s))
                .collect(Collectors.toList());
    }

    private List<ProductionOrderEntity> loadVendorOrders(LocalDate from, LocalDate to) {
        return productionOrderRepository.findActiveOrders().stream()
                .filter(customerAccountService::isLfVendorOrder)
                .filter(order -> order.getVendorShipmentVoidedAt() == null)
                .filter(order -> {
                    LocalDate orderDate = resolveVendorOrderDate(order);
                    return orderDate != null && !orderDate.isBefore(from) && !orderDate.isAfter(to);
                })
                .collect(Collectors.toList());
    }

    private LocalDate resolveVendorOrderDate(ProductionOrderEntity order) {
        if (order.getStartDate() != null) {
            return order.getStartDate();
        }
        return order.getCreatedAt() != null ? order.getCreatedAt().toLocalDate() : null;
    }

    private boolean isCancelledOnlineSale(OnlineSaleEntity sale) {
        String status = safeText(sale.getStatus()).toUpperCase(Locale.ROOT);
        return "CANCELADO".equals(status) || "CANCELADA".equals(status) || "ANULADA".equals(status);
    }

    private BigDecimal sumKioskAmount(List<KioskSaleEntity> sales) {
        return sales.stream().map(s -> safeAmount(s.getTotalAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumOnlineAmount(List<OnlineSaleEntity> sales) {
        return sales.stream().map(s -> safeAmount(s.getTotalAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumVendorAmount(List<ProductionOrderEntity> orders) {
        return orders.stream()
                .map(customerAccountService::estimateVendorOrderTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumAmounts(BigDecimal... values) {
        return Stream.of(values)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumChannelTotals(BigDecimal kiosko, BigDecimal online, BigDecimal vendor) {
        return sumAmounts(kiosko, online, vendor);
    }

    private BigDecimal growthPercent(BigDecimal current, BigDecimal previous) {
        BigDecimal safeCurrent = safeAmount(current);
        BigDecimal safePrevious = safeAmount(previous);
        if (safePrevious.compareTo(BigDecimal.ZERO) <= 0) {
            return safeCurrent.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return safeCurrent.subtract(safePrevious)
                .multiply(BigDecimal.valueOf(100))
                .divide(safePrevious, 1, RoundingMode.HALF_UP);
    }

    private void addUnits(Map<String, BigDecimal> unitsByProduct, String productName, BigDecimal quantity) {
        String name = productName != null && !productName.isBlank() ? productName.trim() : "Sin nombre";
        BigDecimal qty = quantity != null ? quantity : BigDecimal.ZERO;
        unitsByProduct.merge(name, qty, BigDecimal::add);
    }

    private int resolveItemQuantity(ProductionOrderItemEntity item) {
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(item.getSizesData());
        if (!sizes.isEmpty()) {
            return sizes.values().stream()
                    .filter(Objects::nonNull)
                    .mapToInt(BigDecimal::intValue)
                    .sum();
        }
        return item.getQuantity() != null ? item.getQuantity() : 0;
    }

    private BigDecimal toBigDecimal(Integer value) {
        return value != null ? BigDecimal.valueOf(value) : BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private int safeCount(Integer value) {
        return value != null ? value : 0;
    }

    private String safeText(String value) {
        return value != null ? value.trim() : "";
    }

    private String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank() || "all".equalsIgnoreCase(channel.trim())) {
            return null;
        }
        return switch (channel.trim().toLowerCase(Locale.ROOT)) {
            case "kiosko", "kiosk", "kioscos" -> CHANNEL_KIOSKO;
            case "online", "enlinea" -> CHANNEL_ONLINE;
            case "vendedor", "vendor", "lf" -> CHANNEL_VENDOR;
            default -> channel.trim().toUpperCase(Locale.ROOT);
        };
    }

    private DateRange resolveRange(LocalDate startDate, LocalDate endDate) throws BusinessException {
        LocalDate to = endDate != null ? endDate : LocalDate.now();
        LocalDate from = startDate != null ? startDate : to.withDayOfMonth(1);
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        return new DateRange(from, to);
    }

    private DateRange previousPeriod(LocalDate from, LocalDate to) {
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        return new DateRange(previousFrom, previousTo);
    }

    private record DateRange(LocalDate from, LocalDate to) {}
}
