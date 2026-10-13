package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse.CategoryRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskHeatmapResponse.SiteRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/**
 * Mapa de calor de kioscos (docs/SALES-DASHBOARD-CONTRACT.md, addendum 3): la venta diaria de TODOS los sitios
 * {@code kiosk_site} incluidos en reportes, para comparar tendencias entre kioscos y por categoría A/B/C.
 * <p>
 * El dinero sale únicamente de {@link KioskSalesSourceResolver} (solo lectura): histórico antes del go-live efectivo
 * del sitio y POS desde el go-live, empaque incluido; es el mismo número que muestra Finanzas kioscos. No consulta el
 * detalle POS (tickets e ítems): una sola resolución cubre el periodo anterior y el actual de todos los sitios.
 * No admite filtro por kiosko porque sirve justamente para compararlos.
 */
@Service
@RequiredArgsConstructor
public class KioskHeatmapService {

    static final String CACHE_SOURCE = "KIOSK_HEATMAP";
    static final int MAX_DAYS = 400;
    static final String MAX_DAYS_MESSAGE = "El mapa de calor admite un máximo de " + MAX_DAYS + " días.";
    /** Origen de un sitio sin datos en el rango (los demás valores vienen de {@code SiteSales.source()}). */
    static final String SOURCE_NONE = "NONE";

    /** Orden de las categorías en {@code sites} y {@code categories}: A, B, C y al final los sin clasificar (null). */
    private static final List<String> CATEGORY_ORDER = Arrays.asList("A", "B", "C", null);

    /** Categoría (A, B, C, sin categoría), luego mayor venta del periodo y luego nombre sin distinguir mayúsculas. */
    private static final Comparator<SiteRow> SITE_ORDER =
            Comparator.comparingInt((SiteRow row) -> categoryRank(row.getCategory()))
                    .thenComparing(SiteRow::getTotal, Comparator.reverseOrder())
                    .thenComparing(SiteRow::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(SiteRow::getSiteId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final BigDecimal ZERO_MONEY = money(BigDecimal.ZERO);

    private final KioskSiteRepository kioskSiteRepository;
    private final KioskSalesSourceResolver salesSourceResolver;
    private final SalesDashboardCache cache;

    /**
     * @param refresh {@code true} descarta la entrada de caché de ese rango y recalcula
     * @throws BusinessException rango invertido o de más de {@value #MAX_DAYS} días
     */
    public KioskHeatmapResponse getHeatmap(LocalDate startDate, LocalDate endDate, boolean refresh)
            throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        if (ChronoUnit.DAYS.between(range.from(), range.to()) + 1 > MAX_DAYS) {
            throw new BusinessException(MAX_DAYS_MESSAGE);
        }
        String key = SalesDashboardCache.key(CACHE_SOURCE, range.from(), range.to(), null, null);
        return cache.get(key, refresh, () -> build(range));
    }

    KioskHeatmapResponse build(DateRange range) {
        DateRange previous = previousPeriod(range);
        List<KioskSiteEntity> included = kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();

        // Misma fuente que Finanzas: el go-live se calcula una vez y la fuente se resuelve una vez para todos los
        // sitios, sobre el rango que cubre el periodo anterior y el actual.
        Map<Long, LocalDate> goLive = salesSourceResolver.goLiveEffective(included);
        Map<Long, SiteSales> sales = salesSourceResolver.resolve(included, previous.from(), range.to(), goLive);

        List<LocalDate> days = daysOf(range);
        List<SiteRow> sites = new ArrayList<>();
        for (KioskSiteEntity site : included) {
            SiteRow row = siteRow(site, sales.get(site.getId()), range, previous, days);
            if (row.getTotal().signum() != 0 || row.getPreviousTotal().signum() != 0) {
                sites.add(row);
            }
        }
        sites.sort(SITE_ORDER);

        return KioskHeatmapResponse.builder()
                .startDate(range.from())
                .endDate(range.to())
                .previousStartDate(previous.from())
                .previousEndDate(previous.to())
                .days(days)
                .sites(sites)
                .categories(categoryRows(sites))
                .build();
    }

    private static SiteRow siteRow(
            KioskSiteEntity site, SiteSales siteSales, DateRange range, DateRange previous, List<LocalDate> days) {
        Map<LocalDate, BigDecimal> amountByDay = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal previousTotal = BigDecimal.ZERO;
        int daysWithSales = 0;
        if (siteSales != null && siteSales.daily() != null) {
            for (Map.Entry<LocalDate, BigDecimal> entry : siteSales.daily().entrySet()) {
                BigDecimal amount = nz(entry.getValue());
                if (range.contains(entry.getKey())) {
                    amountByDay.merge(entry.getKey(), amount, BigDecimal::add);
                    total = total.add(amount);
                    if (amount.signum() > 0) {
                        daysWithSales++;
                    }
                } else if (previous.contains(entry.getKey())) {
                    previousTotal = previousTotal.add(amount);
                }
            }
        }

        // Un monto por cada día del rango, alineado con `days` (los días sin venta salen como 0.00).
        List<BigDecimal> daily = new ArrayList<>(days.size());
        for (LocalDate day : days) {
            BigDecimal amount = amountByDay.get(day);
            daily.add(amount != null ? money(amount) : ZERO_MONEY);
        }

        BigDecimal totalMoney = money(total);
        BigDecimal previousMoney = money(previousTotal);
        return SiteRow.builder()
                .siteId(site.getId())
                .name(labelOrDefault(site.getName()))
                .category(normalizeSiteCategory(site.getSalesCategory()))
                .locationId(site.getLocationId())
                .source(sourceOf(siteSales, !amountByDay.isEmpty()))
                .total(totalMoney)
                .previousTotal(previousMoney)
                .growthPercent(growthPercent(totalMoney, previousMoney))
                .daysWithSales(daysWithSales)
                .daily(daily)
                .build();
    }

    /** {@code SiteSales.source()}, o "NONE" si el sitio no tiene datos en el rango ni histórico ni POS. */
    private static String sourceOf(SiteSales siteSales, boolean hasDataInCurrentRange) {
        if (siteSales == null || (!hasDataInCurrentRange && !siteSales.hasPos() && !siteSales.hasHist())) {
            return SOURCE_NONE;
        }
        return siteSales.source();
    }

    /** Una fila por categoría presente en {@code sites} (ya filtrados), en orden A, B, C, sin categoría. */
    private static List<CategoryRow> categoryRows(List<SiteRow> sites) {
        BigDecimal grandTotal = sum(sites, SiteRow::getTotal);
        List<CategoryRow> rows = new ArrayList<>();
        for (String category : CATEGORY_ORDER) {
            List<SiteRow> members = sites.stream()
                    .filter(row -> Objects.equals(row.getCategory(), category))
                    .toList();
            if (members.isEmpty()) {
                continue;
            }
            BigDecimal total = sum(members, SiteRow::getTotal);
            BigDecimal previousTotal = sum(members, SiteRow::getPreviousTotal);
            rows.add(CategoryRow.builder()
                    .category(category)
                    .kioskCount(members.size())
                    .total(money(total))
                    .previousTotal(money(previousTotal))
                    .growthPercent(growthPercent(total, previousTotal))
                    .sharePercent(percent(total, grandTotal))
                    .build());
        }
        return rows;
    }

    private static BigDecimal sum(List<SiteRow> rows, Function<SiteRow, BigDecimal> amount) {
        return rows.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int categoryRank(String category) {
        int rank = CATEGORY_ORDER.indexOf(category);
        return rank >= 0 ? rank : CATEGORY_ORDER.size();
    }

    private static List<LocalDate> daysOf(DateRange range) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            days.add(day);
        }
        return days;
    }
}
