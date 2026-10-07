package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.KioskOption;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SaleRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.BreakdownAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.ProductAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.SaleFact;
import com.fossiles.fossilescorebackend.application.util.FinishedProductClassifier;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/**
 * Dashboard de ventas de kioscos. Carga las cabeceras del rango [anterior/6 meses, fin] en una consulta,
 * los ítems del periodo en otra y deriva periodo, periodo anterior, hoy, serie diaria y tendencia en memoria.
 */
@Service
@RequiredArgsConstructor
public class KioskSalesDashboardService {

    private final SalesSourceLoader sourceLoader;
    private final KioskSaleItemRepository kioskSaleItemRepository;
    private final LocationRepository locationRepository;
    private final SalesDashboardCache cache;

    public SalesSourceDetailResponse getDashboard(
            LocalDate startDate, LocalDate endDate, Long kioskLocationId, boolean refresh) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        String key = SalesDashboardCache.key(CHANNEL_KIOSKO, range.from(), range.to(), kioskLocationId);
        return cache.get(key, refresh, () -> build(range, kioskLocationId));
    }

    SalesSourceDetailResponse build(DateRange range, Long kioskLocationId) {
        List<KioskSaleHeaderRow> allHeaders = sourceLoader.loadKioskHeaders(loadFrom(range), range.to());
        List<KioskSaleHeaderRow> scoped = kioskLocationId == null
                ? allHeaders
                : allHeaders.stream().filter(h -> kioskLocationId.equals(h.kioskLocationId())).toList();

        List<KioskSaleHeaderRow> currentAll = allHeaders.stream().filter(h -> range.contains(h.saleDate())).toList();
        List<KioskSaleHeaderRow> current = scoped.stream().filter(h -> range.contains(h.saleDate())).toList();
        Set<Long> currentIds = current.stream().map(KioskSaleHeaderRow::id).collect(Collectors.toSet());

        Map<Long, List<KioskSaleItemRow>> itemsBySale = new HashMap<>();
        if (!currentIds.isEmpty()) {
            for (KioskSaleItemRow item : kioskSaleItemRepository.findRowsBySaleDateBetween(range.from(), range.to())) {
                if (currentIds.contains(item.kioskSaleId())) {
                    itemsBySale.computeIfAbsent(item.kioskSaleId(), id -> new ArrayList<>()).add(item);
                }
            }
        }
        Map<Long, String> codeByProductId = resolveMissingCodes(itemsBySale.values());

        Map<Long, LocationEntity> kiosks = loadKiosks(currentAll);

        List<SaleFact> facts = new ArrayList<>();
        ProductAccumulator topProducts = new ProductAccumulator();
        BreakdownAccumulator byKiosk = new BreakdownAccumulator();
        BreakdownAccumulator byPayment = new BreakdownAccumulator();
        List<SaleRow> rows = new ArrayList<>();

        for (KioskSaleHeaderRow sale : scoped) {
            BigDecimal total = nz(sale.totalAmount());
            if (!range.contains(sale.saleDate())) {
                facts.add(SaleFact.moneyOnly(sale.saleDate(), total));
                continue;
            }
            BigDecimal packaging = BigDecimal.ZERO;
            BigDecimal units = BigDecimal.ZERO;
            List<String> finishedNames = new ArrayList<>();
            List<KioskSaleItemRow> items = itemsBySale.getOrDefault(sale.id(), List.of());
            for (KioskSaleItemRow item : items) {
                if (FinishedProductClassifier.isPackaging(item.productCode(), item.productId(), codeByProductId)) {
                    packaging = packaging.add(nz(item.lineTotal()));
                    continue;
                }
                units = units.add(nz(item.quantity()));
                topProducts.add(item.productId(), item.productCode(), item.productName(),
                        item.quantity(), item.lineTotal());
                String baseName = FinishedProductClassifier.baseProductName(item.productName());
                if (baseName != null && !baseName.isBlank() && !finishedNames.contains(baseName)) {
                    finishedNames.add(baseName);
                }
            }
            facts.add(new SaleFact(sale.saleDate(), total, total.subtract(packaging), packaging,
                    BigDecimal.ZERO, units));

            LocationEntity kiosk = kiosks.get(sale.kioskLocationId());
            byKiosk.add(String.valueOf(sale.kioskLocationId()), kioskLabel(sale.kioskLocationId(), kiosk), total);
            byPayment.add(sale.paymentMethod(), total);
            rows.add(SaleRow.builder()
                    .id(sale.id())
                    .saleDate(sale.saleDate())
                    .reference(sale.saleNumber())
                    .productLabel(productLabel(finishedNames, !items.isEmpty(), "Venta kiosko"))
                    .quantity(units)
                    .totalAmount(money(total))
                    .status(sale.status())
                    .party(kioskLabel(sale.kioskLocationId(), kiosk))
                    .build());
        }

        SalesSourceDetailResponse.SourceKpis kpis = kpis(range, facts, today());
        DateRange previous = previousPeriod(range);
        Map<String, List<SalesSourceDetailResponse.BreakdownRow>> breakdowns = new LinkedHashMap<>();
        breakdowns.put("byKiosk", byKiosk.rows(kpis.getTotalAmount()));
        breakdowns.put("byPaymentMethod", byPayment.rows(kpis.getTotalAmount()));

        return SalesSourceDetailResponse.builder()
                .channel(CHANNEL_KIOSKO)
                .label("Kioskos")
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
                .kioskOptions(buildKioskOptions(currentAll, kiosks))
                .build();
    }

    /** Códigos de catálogo solo para ítems sin productCode (normalmente ninguno: sin consulta extra). */
    private Map<Long, String> resolveMissingCodes(Collection<List<KioskSaleItemRow>> itemLists) {
        Set<Long> missing = new HashSet<>();
        for (List<KioskSaleItemRow> items : itemLists) {
            for (KioskSaleItemRow item : items) {
                if ((item.productCode() == null || item.productCode().isBlank()) && item.productId() != null) {
                    missing.add(item.productId());
                }
            }
        }
        Map<Long, ProductEntity> products = sourceLoader.loadProductsById(missing);
        return FinishedProductClassifier.codesById(products.values());
    }

    private Map<Long, LocationEntity> loadKiosks(List<KioskSaleHeaderRow> sales) {
        Set<Long> kioskIds = sales.stream()
                .map(KioskSaleHeaderRow::kioskLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (kioskIds.isEmpty()) {
            return Map.of();
        }
        return locationRepository.findAllById(kioskIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, row -> row, (a, b) -> a));
    }

    private List<KioskOption> buildKioskOptions(List<KioskSaleHeaderRow> sales, Map<Long, LocationEntity> kiosks) {
        Set<Long> kioskIds = sales.stream()
                .map(KioskSaleHeaderRow::kioskLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return kioskIds.stream()
                .map(id -> {
                    LocationEntity kiosk = kiosks.get(id);
                    return KioskOption.builder()
                            .kioskId(id)
                            .kioskCode(kiosk != null && kiosk.getCode() != null ? kiosk.getCode() : "")
                            .kioskName(kioskLabel(id, kiosk))
                            .build();
                })
                .sorted(Comparator.comparing(KioskOption::getKioskName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String kioskLabel(Long kioskId, LocationEntity kiosk) {
        if (kiosk != null && kiosk.getName() != null && !kiosk.getName().isBlank()) {
            return kiosk.getName();
        }
        return kioskId != null ? "Kiosko " + kioskId : NO_DATA;
    }
}
