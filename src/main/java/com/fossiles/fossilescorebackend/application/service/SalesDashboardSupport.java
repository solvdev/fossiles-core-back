package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.BreakdownRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.DailyPoint;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.ProductRank;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SourceKpis;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.TrendPoint;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.FinishedProductClassifier;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/** Cálculo en memoria compartido por los dashboards de ventas por fuente. */
final class SalesDashboardSupport {

    static final String CHANNEL_KIOSKO = "KIOSKO";
    static final String CHANNEL_ONLINE = "ONLINE";
    static final String CHANNEL_VENDOR = "VENDOR";
    static final int TOP_PRODUCTS_LIMIT = 10;
    static final int RECENT_SALES_LIMIT = 20;
    static final int MONTHLY_TREND_MONTHS = 6;
    static final String NO_DATA = "Sin dato";
    static final String PACKAGING_ONLY_LABEL = "Solo empaque";

    private static final Locale LOCALE_ES_GT = Locale.of("es", "GT");

    private SalesDashboardSupport() {
    }

    record DateRange(LocalDate from, LocalDate to) {
        boolean contains(LocalDate date) {
            return date != null && !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /**
     * Venta válida ya valorizada. Fuera del periodo actual solo importan {@code date} y {@code total}
     * (periodo anterior y tendencia); los desgloses y unidades vienen en cero.
     */
    record SaleFact(
            LocalDate date,
            BigDecimal total,
            BigDecimal product,
            BigDecimal packaging,
            BigDecimal shipping,
            BigDecimal units) {

        static SaleFact moneyOnly(LocalDate date, BigDecimal total) {
            return new SaleFact(date, nz(total), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    static LocalDate today() {
        return GuatemalaDateTime.today();
    }

    static DateRange resolveRange(LocalDate startDate, LocalDate endDate) throws BusinessException {
        LocalDate to = endDate != null ? endDate : today();
        LocalDate from = startDate != null ? startDate : to.withDayOfMonth(1);
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        return new DateRange(from, to);
    }

    static DateRange previousPeriod(DateRange range) {
        long days = range.to().toEpochDay() - range.from().toEpochDay() + 1;
        LocalDate previousTo = range.from().minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        return new DateRange(previousFrom, previousTo);
    }

    static LocalDate trendStart(LocalDate endDate) {
        return YearMonth.from(endDate).minusMonths(MONTHLY_TREND_MONTHS - 1L).atDay(1);
    }

    /** Inicio del rango único de carga: lo más antiguo entre el periodo anterior y el primer mes de la tendencia. */
    static LocalDate loadFrom(DateRange range) {
        LocalDate previousFrom = previousPeriod(range).from();
        LocalDate trendFrom = trendStart(range.to());
        return previousFrom.isBefore(trendFrom) ? previousFrom : trendFrom;
    }

    static SourceKpis kpis(DateRange range, List<SaleFact> facts, LocalDate today) {
        DateRange previous = previousPeriod(range);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal product = BigDecimal.ZERO;
        BigDecimal packaging = BigDecimal.ZERO;
        BigDecimal shipping = BigDecimal.ZERO;
        BigDecimal units = BigDecimal.ZERO;
        BigDecimal daily = BigDecimal.ZERO;
        BigDecimal previousTotal = BigDecimal.ZERO;
        int count = 0;
        for (SaleFact fact : facts) {
            if (range.contains(fact.date())) {
                count++;
                total = total.add(fact.total());
                product = product.add(fact.product());
                packaging = packaging.add(fact.packaging());
                shipping = shipping.add(fact.shipping());
                units = units.add(fact.units());
                if (today.equals(fact.date())) {
                    daily = daily.add(fact.total());
                }
            } else if (previous.contains(fact.date())) {
                previousTotal = previousTotal.add(fact.total());
            }
        }
        return SourceKpis.builder()
                .totalAmount(money(total))
                .productAmount(money(product))
                .packagingAmount(money(packaging))
                .shippingAmount(money(shipping))
                .historicalAmount(money(BigDecimal.ZERO))
                .previousTotalAmount(money(previousTotal))
                .growthPercent(growthPercent(total, previousTotal))
                .dailyAmount(money(daily))
                .salesCount(count)
                .unitsFinished(units.setScale(2, RoundingMode.HALF_UP))
                .avgTicket(average(total, count))
                .build();
    }

    /**
     * KPIs del canal KIOSKO. El dinero (total, periodo anterior y hoy) viene de la fuente de Finanzas kioscos
     * (histórico + POS); el desglose, los tickets y las unidades solo existen para el detalle POS.
     * <p>
     * {@code historicalAmount} es la parte del total sin desglose (histórico): {@code max(0, total - producto - empaque)},
     * por lo que {@code producto + empaque + envío(0) + histórico == total}. El ticket promedio es sobre la base POS:
     * {@code (producto + empaque) / tickets POS}.
     */
    static SourceKpis kioskKpis(
            BigDecimal total,
            BigDecimal previousTotal,
            BigDecimal todayAmount,
            BigDecimal posProduct,
            BigDecimal posPackaging,
            BigDecimal posUnits,
            int posTickets) {
        BigDecimal totalMoney = money(total);
        BigDecimal productMoney = money(posProduct);
        BigDecimal packagingMoney = money(posPackaging);
        BigDecimal historical = money(totalMoney.subtract(productMoney).subtract(packagingMoney).max(BigDecimal.ZERO));
        return SourceKpis.builder()
                .totalAmount(totalMoney)
                .productAmount(productMoney)
                .packagingAmount(packagingMoney)
                .shippingAmount(money(BigDecimal.ZERO))
                .historicalAmount(historical)
                .previousTotalAmount(money(previousTotal))
                .growthPercent(growthPercent(total, previousTotal))
                .dailyAmount(money(todayAmount))
                .salesCount(posTickets)
                .unitsFinished(nz(posUnits).setScale(2, RoundingMode.HALF_UP))
                .avgTicket(average(nz(posProduct).add(nz(posPackaging)), posTickets))
                .build();
    }

    static List<DailyPoint> dailySeries(DateRange range, List<SaleFact> facts) {
        Map<LocalDate, BigDecimal> amountByDay = new HashMap<>();
        Map<LocalDate, Integer> countByDay = new HashMap<>();
        for (SaleFact fact : facts) {
            if (range.contains(fact.date())) {
                amountByDay.merge(fact.date(), fact.total(), BigDecimal::add);
                countByDay.merge(fact.date(), 1, Integer::sum);
            }
        }
        return dailySeries(range, amountByDay, countByDay);
    }

    /**
     * Serie diaria con importe y conteo de fuentes distintas (kioscos: dinero de Finanzas + tickets POS).
     * Un punto por cada día del rango; los días ausentes de los mapas salen con {@code amount: 0, count: 0}.
     */
    static List<DailyPoint> dailySeries(
            DateRange range, Map<LocalDate, BigDecimal> amountByDay, Map<LocalDate, Integer> countByDay) {
        List<DailyPoint> points = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            points.add(DailyPoint.builder()
                    .date(day)
                    .amount(money(amountByDay.getOrDefault(day, BigDecimal.ZERO)))
                    .count(countByDay.getOrDefault(day, 0))
                    .build());
        }
        return points;
    }

    static List<TrendPoint> monthlyTrend(LocalDate endDate, List<SaleFact> facts) {
        YearMonth endMonth = YearMonth.from(endDate);
        Map<YearMonth, BigDecimal> amountByMonth = new HashMap<>();
        for (SaleFact fact : facts) {
            if (fact.date() != null) {
                amountByMonth.merge(YearMonth.from(fact.date()), fact.total(), BigDecimal::add);
            }
        }
        List<TrendPoint> points = new ArrayList<>();
        for (int offset = MONTHLY_TREND_MONTHS - 1; offset >= 0; offset--) {
            YearMonth month = endMonth.minusMonths(offset);
            points.add(TrendPoint.builder()
                    .label(monthLabel(month))
                    .year(month.getYear())
                    .month(month.getMonthValue())
                    .amount(money(amountByMonth.getOrDefault(month, BigDecimal.ZERO)))
                    .build());
        }
        return points;
    }

    /** Tres letras en minúscula ("ene", "sep"), independiente de la variante de CLDR del JDK. */
    static String monthLabel(YearMonth month) {
        String name = month.getMonth().getDisplayName(TextStyle.FULL, LOCALE_ES_GT);
        return name.substring(0, Math.min(3, name.length())).toLowerCase(LOCALE_ES_GT);
    }

    static BigDecimal growthPercent(BigDecimal current, BigDecimal previous) {
        BigDecimal safeCurrent = nz(current);
        BigDecimal safePrevious = nz(previous);
        if (safePrevious.compareTo(BigDecimal.ZERO) <= 0) {
            return safeCurrent.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return safeCurrent.subtract(safePrevious)
                .multiply(BigDecimal.valueOf(100))
                .divide(safePrevious, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return nz(part).multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal average(BigDecimal total, int count) {
        if (count <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return nz(total).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    static String labelOrDefault(String value) {
        return value != null && !value.isBlank() ? value.trim() : NO_DATA;
    }

    /**
     * Categoría de ventas de un sitio de Finanzas kioscos ({@code kiosk_site.sales_category}) tal como la expone el
     * dashboard: solo "A", "B" o "C" (sin importar mayúsculas ni espacios); vacío o cualquier otro valor = null
     * (sin clasificar). Es solo lectura: no valida ni rechaza, a diferencia de la edición en Finanzas.
     */
    static String normalizeSiteCategory(String raw) {
        if (raw == null) {
            return null;
        }
        String category = raw.trim().toUpperCase(Locale.ROOT);
        return category.equals("A") || category.equals("B") || category.equals("C") ? category : null;
    }

    /** Etiqueta "Billetera +2 más" solo con terminados; si solo hay empaques, "Solo empaque". */
    static String productLabel(List<String> finishedNames, boolean hasAnyItem, String emptyFallback) {
        if (finishedNames.isEmpty()) {
            return hasAnyItem ? PACKAGING_ONLY_LABEL : emptyFallback;
        }
        if (finishedNames.size() == 1) {
            return finishedNames.get(0);
        }
        return finishedNames.get(0) + " +" + (finishedNames.size() - 1) + " más";
    }

    static <T> List<List<T>> partition(Collection<T> values, int size) {
        List<T> all = new ArrayList<>(values);
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            parts.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return parts;
    }

    /** Acumulador de desglose por clave de texto (agrupa sin distinguir mayúsculas). */
    static final class BreakdownAccumulator {
        private final Map<String, Row> rows = new LinkedHashMap<>();

        void add(String rawKey, String label, BigDecimal amount) {
            String key = rawKey != null && !rawKey.isBlank() ? rawKey.trim() : NO_DATA;
            Row row = rows.computeIfAbsent(key.toUpperCase(Locale.ROOT), k -> new Row(key, labelOrDefault(label)));
            row.count++;
            row.amount = row.amount.add(nz(amount));
        }

        void add(String rawLabel, BigDecimal amount) {
            add(rawLabel, rawLabel, amount);
        }

        List<BreakdownRow> rows(BigDecimal totalAmount) {
            return rows.values().stream()
                    .sorted(Comparator.comparing((Row r) -> r.amount).reversed())
                    .map(r -> BreakdownRow.builder()
                            .key(r.key)
                            .label(r.label)
                            .count(r.count)
                            .amount(money(r.amount))
                            .sharePercent(percent(r.amount, totalAmount))
                            .build())
                    .toList();
        }

        private static final class Row {
            private final String key;
            private final String label;
            private int count;
            private BigDecimal amount = BigDecimal.ZERO;

            private Row(String key, String label) {
                this.key = key;
                this.label = label;
            }
        }
    }

    /** Acumulador del ranking de productos terminados, agrupado por producto base. */
    static final class ProductAccumulator {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        void add(Long productId, String productCode, String productName, BigDecimal units, BigDecimal amount) {
            String baseName = FinishedProductClassifier.baseProductName(productName);
            String key;
            if (productId != null) {
                key = "id:" + productId;
            } else if (productCode != null && !productCode.isBlank()) {
                key = "code:" + productCode.trim().toUpperCase(Locale.ROOT);
            } else {
                key = "name:" + (baseName != null ? baseName.toLowerCase(Locale.ROOT) : "");
            }
            Entry entry = entries.computeIfAbsent(key, k -> new Entry(productId, productCode,
                    baseName != null && !baseName.isBlank() ? baseName : "Sin nombre"));
            entry.units = entry.units.add(nz(units));
            entry.amount = entry.amount.add(nz(amount));
        }

        List<ProductRank> top(int limit) {
            return entries.values().stream()
                    .sorted(Comparator.comparing((Entry e) -> e.units).reversed()
                            .thenComparing(Comparator.comparing((Entry e) -> e.amount).reversed()))
                    .limit(limit)
                    .map(e -> ProductRank.builder()
                            .productId(e.productId)
                            .productCode(e.productCode)
                            .productName(e.name)
                            .units(e.units.setScale(2, RoundingMode.HALF_UP))
                            .amount(money(e.amount))
                            .build())
                    .toList();
        }

        private static final class Entry {
            private final Long productId;
            private final String productCode;
            private final String name;
            private BigDecimal units = BigDecimal.ZERO;
            private BigDecimal amount = BigDecimal.ZERO;

            private Entry(Long productId, String productCode, String name) {
                this.productId = productId;
                this.productCode = productCode;
                this.name = name;
            }
        }
    }
}
