package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SaleRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.BreakdownAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.ProductAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.SaleFact;
import com.fossiles.fossilescorebackend.application.util.FinishedProductClassifier;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/** Dashboard de ventas online: una consulta de cabeceras del rango completo y una de ítems del periodo. */
@Service
@RequiredArgsConstructor
public class OnlineSalesDashboardService {

    private final SalesSourceLoader sourceLoader;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final SalesDashboardCache cache;

    public SalesSourceDetailResponse getDashboard(
            LocalDate startDate, LocalDate endDate, boolean refresh) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        String key = SalesDashboardCache.key(CHANNEL_ONLINE, range.from(), range.to(), null);
        return cache.get(key, refresh, () -> build(range));
    }

    SalesSourceDetailResponse build(DateRange range) {
        List<OnlineSaleEntity> sales = sourceLoader.loadOnlineSales(loadFrom(range), range.to());
        Set<Long> currentIds = sales.stream()
                .filter(s -> range.contains(s.getSaleDate()))
                .map(OnlineSaleEntity::getId)
                .collect(Collectors.toSet());

        Map<Long, List<OnlineSaleItemEntity>> itemsBySale = new HashMap<>();
        if (!currentIds.isEmpty()) {
            for (OnlineSaleItemEntity item : onlineSaleItemRepository.findBySaleDateBetween(range.from(), range.to())) {
                if (currentIds.contains(item.getOnlineSaleId())) {
                    itemsBySale.computeIfAbsent(item.getOnlineSaleId(), id -> new ArrayList<>()).add(item);
                }
            }
        }
        Map<Long, String> codeByProductId = resolveMissingCodes(range, sales, itemsBySale);

        List<SaleFact> facts = new ArrayList<>();
        ProductAccumulator topProducts = new ProductAccumulator();
        BreakdownAccumulator bySeller = new BreakdownAccumulator();
        BreakdownAccumulator bySocialNetwork = new BreakdownAccumulator();
        BreakdownAccumulator byPayment = new BreakdownAccumulator();
        BreakdownAccumulator byStatus = new BreakdownAccumulator();
        List<SaleRow> rows = new ArrayList<>();

        for (OnlineSaleEntity sale : sales) {
            BigDecimal total = nz(sale.getTotalAmount());
            if (!range.contains(sale.getSaleDate())) {
                facts.add(SaleFact.moneyOnly(sale.getSaleDate(), total));
                continue;
            }
            BigDecimal shipping = nz(sale.getShippingCost());
            BigDecimal packaging = BigDecimal.ZERO;
            BigDecimal units = BigDecimal.ZERO;
            List<String> finishedNames = new ArrayList<>();
            List<OnlineSaleItemEntity> items = itemsBySale.getOrDefault(sale.getId(), List.of());

            if (items.isEmpty()) {
                // Venta legacy sin ítems: la cabecera hace de único producto.
                if (FinishedProductClassifier.isPackaging(sale.getProductCode(), sale.getProductId(), codeByProductId)) {
                    packaging = total.subtract(shipping);
                } else {
                    BigDecimal qty = BigDecimal.valueOf(sale.getQuantity() != null ? sale.getQuantity() : 0);
                    units = qty;
                    topProducts.add(sale.getProductId(), sale.getProductCode(), sale.getProductName(),
                            qty, total.subtract(shipping));
                    addFinishedName(finishedNames, sale.getProductName());
                }
            }
            for (OnlineSaleItemEntity item : items) {
                BigDecimal qty = BigDecimal.valueOf(item.getQuantity() != null ? item.getQuantity() : 0);
                BigDecimal subtotal = itemSubtotal(item, qty);
                if (FinishedProductClassifier.isPackaging(item.getProductCode(), item.getProductId(), codeByProductId)) {
                    packaging = packaging.add(subtotal);
                    continue;
                }
                units = units.add(qty);
                topProducts.add(item.getProductId(), item.getProductCode(), item.getProductName(), qty, subtotal);
                addFinishedName(finishedNames, item.getProductName());
            }
            BigDecimal product = total.subtract(shipping).subtract(packaging);
            facts.add(new SaleFact(sale.getSaleDate(), total, product, packaging, shipping, units));

            bySeller.add(sale.getSalesperson(), total);
            bySocialNetwork.add(sale.getSocialNetwork(), total);
            byPayment.add(sale.getPaymentMethod(), total);
            byStatus.add(sale.getStatus(), total);
            boolean hasAnyItem = !items.isEmpty()
                    || (sale.getProductName() != null && !sale.getProductName().isBlank());
            rows.add(SaleRow.builder()
                    .id(sale.getId())
                    .saleDate(sale.getSaleDate())
                    .reference(sale.getSaleNumber())
                    .productLabel(productLabel(finishedNames, hasAnyItem, "Venta online"))
                    .quantity(units)
                    .totalAmount(money(total))
                    .status(sale.getStatus())
                    .party(sale.getSalesperson())
                    .build());
        }

        SalesSourceDetailResponse.SourceKpis kpis = kpis(range, facts, today());
        DateRange previous = previousPeriod(range);
        Map<String, List<SalesSourceDetailResponse.BreakdownRow>> breakdowns = new LinkedHashMap<>();
        breakdowns.put("bySeller", bySeller.rows(kpis.getTotalAmount()));
        breakdowns.put("bySocialNetwork", bySocialNetwork.rows(kpis.getTotalAmount()));
        breakdowns.put("byPaymentMethod", byPayment.rows(kpis.getTotalAmount()));
        breakdowns.put("byStatus", byStatus.rows(kpis.getTotalAmount()));

        return SalesSourceDetailResponse.builder()
                .channel(CHANNEL_ONLINE)
                .label("Online")
                .startDate(range.from())
                .endDate(range.to())
                .previousStartDate(previous.from())
                .previousEndDate(previous.to())
                .kpis(kpis)
                .dailySeries(dailySeries(range, facts))
                .monthlyTrend(monthlyTrend(range.to(), facts))
                .topProducts(topProducts.top(TOP_PRODUCTS_LIMIT))
                .recentSales(rows.stream()
                        .sorted(Comparator.comparing(SaleRow::getSaleDate, Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(SaleRow::getId, Comparator.reverseOrder()))
                        .limit(RECENT_SALES_LIMIT)
                        .toList())
                .breakdowns(breakdowns)
                .kioskOptions(null)
                .build();
    }

    private static BigDecimal itemSubtotal(OnlineSaleItemEntity item, BigDecimal qty) {
        if (item.getSubtotal() != null) {
            return item.getSubtotal();
        }
        return nz(item.getUnitPrice()).multiply(qty);
    }

    private static void addFinishedName(List<String> names, String productName) {
        String baseName = FinishedProductClassifier.baseProductName(productName);
        if (baseName != null && !baseName.isBlank() && !names.contains(baseName)) {
            names.add(baseName);
        }
    }

    /** Códigos de catálogo solo para ítems / ventas sin productCode (normalmente ninguno: sin consulta extra). */
    private Map<Long, String> resolveMissingCodes(
            DateRange range,
            List<OnlineSaleEntity> sales, Map<Long, List<OnlineSaleItemEntity>> itemsBySale) {
        Set<Long> missing = new HashSet<>();
        for (List<OnlineSaleItemEntity> items : itemsBySale.values()) {
            for (OnlineSaleItemEntity item : items) {
                if ((item.getProductCode() == null || item.getProductCode().isBlank()) && item.getProductId() != null) {
                    missing.add(item.getProductId());
                }
            }
        }
        for (OnlineSaleEntity sale : sales) {
            if (!range.contains(sale.getSaleDate())) {
                continue;
            }
            boolean noItems = !itemsBySale.containsKey(sale.getId());
            if (noItems && (sale.getProductCode() == null || sale.getProductCode().isBlank())
                    && sale.getProductId() != null) {
                missing.add(sale.getProductId());
            }
        }
        return FinishedProductClassifier.codesById(sourceLoader.loadProductsById(missing).values());
    }
}
