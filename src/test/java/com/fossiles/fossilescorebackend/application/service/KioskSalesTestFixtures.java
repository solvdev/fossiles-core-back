package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskDailySalesHistEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskDailySalesHistRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosSalesAggregateRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;

/**
 * Datos de prueba de ventas de kioscos y simulación en memoria de las consultas que usan el dashboard (cabeceras e
 * ítems POS) y la fuente de Finanzas (agregados POS, primera venta real e histórico diario), con el mismo criterio
 * de venta real que el SQL: {@code test_sale = false} y estado distinto de VOID/CANCELLED. Así el dinero (resolver)
 * y el detalle (cabeceras) de un test parten siempre de las mismas ventas, igual que en la base de datos.
 */
final class KioskSalesTestFixtures {

    private KioskSalesTestFixtures() {
    }

    static KioskSiteEntity site(long id, String name, Long locationId) {
        return KioskSiteEntity.builder().id(id).name(name).locationId(locationId).build();
    }

    static KioskDailySalesHistEntity hist(long siteId, LocalDate date, String amount) {
        return KioskDailySalesHistEntity.builder().siteId(siteId).saleDate(date).amount(new BigDecimal(amount)).build();
    }

    static KioskSaleHeaderRow header(
            long id, long locationId, LocalDate date, String total, String paymentMethod, String status, boolean test) {
        return new KioskSaleHeaderRow(id, "V-" + id, locationId, date, date.atTime(12, 0), paymentMethod, status,
                BigDecimal.ONE, new BigDecimal(total), test);
    }

    static KioskSaleItemRow item(long saleId, Long productId, String code, String name, String qty, String line) {
        return new KioskSaleItemRow(saleId, productId, code, name, new BigDecimal(qty), new BigDecimal(line));
    }

    /**
     * Simula {@code findHeaderRowsBySaleDateBetween} (todas las cabeceras del rango; el filtro de venta válida lo hace
     * el servicio) y los agregados de Finanzas {@code sumRealSalesByLocationAndDate} / {@code findFirstRealSaleDateByLocation}.
     */
    static void stubPosSales(
            KioskSaleRepository headerRepository,
            KioskPosSalesAggregateRepository aggregateRepository,
            List<KioskSaleHeaderRow> headers) {
        doAnswer(invocation -> {
            LocalDate from = invocation.getArgument(0);
            LocalDate to = invocation.getArgument(1);
            return headers.stream()
                    .filter(h -> inRange(h.saleDate(), from, to))
                    .sorted(Comparator.comparing(KioskSaleHeaderRow::soldAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(KioskSaleHeaderRow::id, Comparator.reverseOrder()))
                    .toList();
        }).when(headerRepository).findHeaderRowsBySaleDateBetween(any(), any());
        doAnswer(invocation -> {
            Collection<Long> locationIds = invocation.getArgument(0);
            Map<Long, LocalDate> first = new TreeMap<>();
            realSales(headers)
                    .filter(h -> locationIds.contains(h.kioskLocationId()))
                    .forEach(h -> first.merge(h.kioskLocationId(), h.saleDate(), (a, b) -> a.isBefore(b) ? a : b));
            List<Object[]> rows = new ArrayList<>();
            first.forEach((locationId, date) -> rows.add(new Object[]{locationId, date}));
            return rows;
        }).when(aggregateRepository).findFirstRealSaleDateByLocation(anyCollection());
        doAnswer(invocation -> {
            Collection<Long> locationIds = invocation.getArgument(0);
            LocalDate from = invocation.getArgument(1);
            LocalDate to = invocation.getArgument(2);
            Map<Long, Map<LocalDate, BigDecimal>> sums = new TreeMap<>();
            realSales(headers)
                    .filter(h -> locationIds.contains(h.kioskLocationId()) && inRange(h.saleDate(), from, to))
                    .forEach(h -> sums.computeIfAbsent(h.kioskLocationId(), k -> new TreeMap<>())
                            .merge(h.saleDate(), h.totalAmount(), BigDecimal::add));
            List<Object[]> rows = new ArrayList<>();
            sums.forEach((locationId, byDate) ->
                    byDate.forEach((date, amount) -> rows.add(new Object[]{locationId, date, amount})));
            return rows;
        }).when(aggregateRepository).sumRealSalesByLocationAndDate(anyCollection(), any(), any());
    }

    /** Simula {@code findRowsBySaleDateBetween}: ítems de las ventas cuyo {@code saleDate} cae en el rango. */
    static void stubPosItems(
            KioskSaleItemRepository itemRepository, List<KioskSaleHeaderRow> headers, List<KioskSaleItemRow> items) {
        Map<Long, LocalDate> dateBySale = headers.stream()
                .collect(Collectors.toMap(KioskSaleHeaderRow::id, KioskSaleHeaderRow::saleDate, (a, b) -> a));
        doAnswer(invocation -> {
            LocalDate from = invocation.getArgument(0);
            LocalDate to = invocation.getArgument(1);
            return items.stream().filter(i -> inRange(dateBySale.get(i.kioskSaleId()), from, to)).toList();
        }).when(itemRepository).findRowsBySaleDateBetween(any(), any());
    }

    /** Simula {@code findBySitesAndRange} del histórico diario. */
    static void stubHist(KioskDailySalesHistRepository histRepository, List<KioskDailySalesHistEntity> rows) {
        doAnswer(invocation -> {
            Collection<Long> siteIds = invocation.getArgument(0);
            LocalDate from = invocation.getArgument(1);
            LocalDate to = invocation.getArgument(2);
            return rows.stream()
                    .filter(r -> siteIds.contains(r.getSiteId()) && inRange(r.getSaleDate(), from, to))
                    .toList();
        }).when(histRepository).findBySitesAndRange(anyCollection(), any(), any());
    }

    private static Stream<KioskSaleHeaderRow> realSales(List<KioskSaleHeaderRow> headers) {
        return headers.stream().filter(h -> Boolean.FALSE.equals(h.testSale()) && !isVoidOrCancelled(h.status()));
    }

    private static boolean isVoidOrCancelled(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("VOID") || normalized.equals("CANCELLED");
    }

    private static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /** Suma {@code total()} de cada sitio en el rango, tal como la mostraría Finanzas kioscos. */
    static BigDecimal financeTotal(Map<Long, KioskSalesSourceResolver.SiteSales> sales, LocalDate from, LocalDate to) {
        return sales.values().stream()
                .map(s -> s.totalBetween(from, to))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
