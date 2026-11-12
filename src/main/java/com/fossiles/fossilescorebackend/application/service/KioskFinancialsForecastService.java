package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsForecastResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.application.util.KioskEffectiveGoals;
import com.fossiles.fossilescorebackend.application.util.KioskForecastMath;
import com.fossiles.fossilescorebackend.application.util.KioskPnlCalculator;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPeriodConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPeriodConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proyecciones de Finanzas por kiosco, sencillas y explicables (ver {@link KioskForecastMath}):
 * cierre proyectado del mes en curso, proyección del año siguiente y P&L proyectado con los costos y tasas vigentes.
 * Sólo lee: no escribe nada.
 */
@Service
@RequiredArgsConstructor
public class KioskFinancialsForecastService {

    /** Meses hacia atrás para encontrar costos y tasas vigentes de un kiosco. */
    private static final int COST_LOOKBACK_MONTHS = 12;
    /** Un kiosco sin ventas en este plazo se considera inactivo y no se proyecta. */
    private static final int ACTIVITY_DAYS = 28;

    private final KioskFinancialsAccessGuard guard;
    private final KioskSiteRepository siteRepository;
    private final KioskSalesSourceResolver salesSourceResolver;
    private final KioskPeriodConfigRepository configRepository;
    private final KioskFixedCostRepository fixedCostRepository;
    private final KioskGoalModuleReader goalReader;
    private final KioskFinancialsSettingsService settingsService;

    /** Costos y tasas de referencia de un kiosco: el último mes (hasta el actual) con las 4 tasas configuradas. */
    private record Reference(YearMonth from, KioskPnlCalculator.Rates rates, Map<String, BigDecimal> fixed) {
    }

    /** Configuración de los dos años que se consultan. */
    private static final class Config {
        final Map<String, KioskPeriodConfigEntity> configs = new HashMap<>();
        final Map<String, Map<String, BigDecimal>> costs = new HashMap<>();

        static String key(Long siteId, int year, int month) {
            return siteId + ":" + year + ":" + month;
        }
    }

    // ------------------------------------------------------------------ cierre del mes en curso

    @Transactional(readOnly = true)
    public KioskFinancialsForecastResponse.MonthEnd monthEnd(String siteIdsCsv, String asOf) throws BusinessException {
        guard.assertCanView();
        LocalDate today = resolveToday(asOf);
        YearMonth ym = YearMonth.from(today);
        List<KioskSiteEntity> sites = selectSites(siteIdsCsv);

        LocalDate monthStart = ym.atDay(1);
        LocalDate from = monthStart.isBefore(today.minusDays(KioskForecastMath.WINDOW_DAYS)) ? monthStart
                : today.minusDays(KioskForecastMath.WINDOW_DAYS);
        Map<Long, SiteSales> sales = salesSourceResolver.resolve(sites, from, today);
        Config cfg = loadConfig(today.getYear());
        KioskEffectiveGoals goals = goalReader.forYear(today.getYear(), sites);
        String beMode = settingsService.breakEvenMode();
        BigDecimal flat = KioskFinancialsReportService.BREAK_EVEN_FLAT.equals(beMode) ? KioskPnlCalculator.FLAT_VARIABLE_RATE : null;

        List<KioskFinancialsForecastResponse.Site> out = new ArrayList<>();
        double sumMtd = 0, sumToDate = 0, sumProjected = 0, sumGoal = 0, sumGoalProjected = 0, sumCost = 0, sumDiff = 0;
        int projectedCount = 0, withoutProjection = 0;
        for (KioskSiteEntity site : sites) {
            SiteSales ss = sales.get(site.getId());
            if (!isActive(site, ss, today)) {
                continue;
            }
            KioskForecastMath.MonthEnd me = KioskForecastMath.monthEnd(ss.daily(), today, ss.goLive());
            KioskPeriodConfigEntity monthCfg = cfg.configs.get(Config.key(site.getId(), ym.getYear(), ym.getMonthValue()));
            BigDecimal goal = goals.goal(site.getId(), ym.getMonthValue(), monthCfg);

            KioskFinancialsForecastResponse.Site.SiteBuilder b = KioskFinancialsForecastResponse.Site.builder()
                    .siteId(site.getId()).name(site.getName())
                    .mtd(money(me.mtd())).actualToDate(money(me.actualToDate()))
                    .method(me.method()).sampleDays(me.sampleDays())
                    .goal(goal == null ? null : goal.setScale(2, RoundingMode.HALF_UP));
            sumMtd += me.mtd();
            sumToDate += me.actualToDate();
            if (me.projected() == null) {
                withoutProjection++;
                out.add(b.build());
                continue;
            }
            projectedCount++;
            sumProjected += me.projected();
            b.projected(money(me.projected())).low(money(me.low())).high(money(me.high()));
            if (goal != null && goal.signum() > 0) {
                b.goalPctProjected(ratio(me.projected(), goal.doubleValue()));
                b.goalPctToDate(ratio(me.actualToDate(), goal.doubleValue()));
                sumGoal += goal.doubleValue();
                sumGoalProjected += me.projected();
            }
            Reference ref = reference(cfg, site.getId(), ym);
            if (ref != null) {
                KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(
                        BigDecimal.valueOf(me.projected()), ref.rates(), ref.fixed(), me.daysInMonth(), flat,
                        KioskPnlCalculator.CommissionPolicy.forPeriod(ym.getYear(), ym.getMonthValue()), goal);
                b.costsFrom(ref.from().toString())
                        .totalCost(KioskPnlCalculator.round2(r.totalCost()))
                        .difference(KioskPnlCalculator.round2(r.difference()))
                        .margin(KioskPnlCalculator.round4(r.margin()))
                        .breakEven(KioskPnlCalculator.round2(r.breakEven()))
                        .belowBreakEven(r.breakEven() != null && BigDecimal.valueOf(me.projected()).compareTo(r.breakEven()) < 0);
                sumCost += r.totalCost().doubleValue();
                sumDiff += r.difference().doubleValue();
            }
            out.add(b.build());
        }

        KioskFinancialsForecastResponse.Totals totals = KioskFinancialsForecastResponse.Totals.builder()
                .mtd(money(sumMtd)).actualToDate(money(sumToDate)).projected(money(sumProjected))
                .goal(money(sumGoal))
                .goalPctProjected(sumGoal > 0 ? ratio(sumGoalProjected, sumGoal) : null)
                .totalCost(money(sumCost)).difference(money(sumDiff))
                .margin(sumProjected > 0 ? ratio(sumDiff, sumProjected) : null)
                .sitesProjected(projectedCount).sitesWithoutProjection(withoutProjection)
                .build();
        return KioskFinancialsForecastResponse.MonthEnd.builder()
                .asOf(today).year(ym.getYear()).month(ym.getMonthValue())
                .daysElapsed(today.getDayOfMonth() - 1).daysRemaining(ym.lengthOfMonth() - today.getDayOfMonth() + 1)
                .daysInMonth(ym.lengthOfMonth()).breakEvenMode(beMode).sites(out).totals(totals).build();
    }

    // ------------------------------------------------------------------ año siguiente

    /**
     * @param growthPct si viene, reemplaza el crecimiento de todos los kioscos (p. ej. 8 = +8 %); entre -50 y +100.
     */
    @Transactional(readOnly = true)
    public KioskFinancialsForecastResponse.NextYear nextYear(String siteIdsCsv, Integer targetYear, BigDecimal growthPct,
                                                             String asOf) throws BusinessException {
        guard.assertCanView();
        LocalDate today = resolveToday(asOf);
        int baseYear = today.getYear();
        int target = targetYear == null ? baseYear + 1 : targetYear;
        if (target != baseYear + 1) {
            throw new BusinessException("Sólo se proyecta el año siguiente (" + (baseYear + 1) + ").");
        }
        Double overrideFactor = null;
        if (growthPct != null) {
            double pct = growthPct.doubleValue();
            if (pct < -50 || pct > 100) {
                throw new BusinessException("El crecimiento debe estar entre -50 % y +100 %.");
            }
            overrideFactor = 1 + pct / 100.0;
        }
        List<KioskSiteEntity> sites = selectSites(siteIdsCsv);
        int currentMonth = today.getMonthValue();
        LocalDate yesterday = today.minusDays(1);

        Map<Long, SiteSales> sales = salesSourceResolver.resolve(sites, LocalDate.of(baseYear - 1, 1, 1), today);
        Config cfg = loadConfig(baseYear);
        String beMode = settingsService.breakEvenMode();
        BigDecimal flat = KioskFinancialsReportService.BREAK_EVEN_FLAT.equals(beMode) ? KioskPnlCalculator.FLAT_VARIABLE_RATE : null;

        // 1) Datos por kiosco: meses del año anterior y del actual, y crecimiento propio
        record SiteData(KioskSiteEntity site, Double[] previous, Double[] current, KioskForecastMath.Growth ownGrowth,
                        double curSum, double baseSum) {
        }
        List<SiteData> data = new ArrayList<>();
        for (KioskSiteEntity site : sites) {
            SiteSales ss = sales.get(site.getId());
            if (!isActive(site, ss, today)) {
                continue;
            }
            NavigableMap<LocalDate, BigDecimal> daily = ss.daily();
            Double[] previous = monthlySums(daily, baseYear - 1, 12);
            Double[] current = monthlySums(daily, baseYear, currentMonth - 1);
            KioskForecastMath.MonthEnd me = KioskForecastMath.monthEnd(daily, today, ss.goLive());
            current[currentMonth - 1] = me.projected();

            // Crecimiento propio: mismas fechas, desde que el kiosco existía el año anterior hasta ayer
            LocalDate firstBase = daily.entrySet().stream()
                    .filter(e -> e.getKey().getYear() == baseYear - 1 && e.getValue().signum() > 0)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            double curSum = 0, baseSum = 0;
            int comparable = 0, baseDays = 0;
            if (firstBase != null) {
                LocalDate start = LocalDate.of(baseYear, 1, 1);
                LocalDate firstComparable = firstBase.plusYears(1);
                if (firstComparable.isAfter(start)) {
                    start = firstComparable;
                }
                for (LocalDate d = start; !d.isAfter(yesterday); d = d.plusDays(1)) {
                    comparable++;
                    BigDecimal base = daily.get(d.minusYears(1));
                    BigDecimal cur = daily.get(d);
                    baseSum += base == null ? 0 : base.doubleValue();
                    curSum += cur == null ? 0 : cur.doubleValue();
                    if (base != null && base.signum() > 0) {
                        baseDays++;
                    }
                }
            }
            KioskForecastMath.Growth own = KioskForecastMath.siteGrowth(curSum, baseSum, comparable, baseDays);
            data.add(new SiteData(site, previous, current, own, curSum, baseSum));
        }

        // 2) Crecimiento global (mismos kioscos: sólo los que tienen base comparable) e índice estacional
        double companyCur = 0, companyBase = 0;
        for (SiteData d : data) {
            if (d.ownGrowth() != null) {
                companyCur += d.curSum();
                companyBase += d.baseSum();
            }
        }
        KioskForecastMath.Growth company = companyBase > 0 ? KioskForecastMath.clamp(companyCur / companyBase) : null;
        List<Double[]> previousYears = data.stream().map(SiteData::previous).collect(Collectors.toList());
        double[] seasonal = KioskForecastMath.seasonalIndex(previousYears);

        // 3) Proyección y P&L por kiosco
        List<KioskFinancialsForecastResponse.SiteYear> siteYears = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        double[] totBase = new double[12], totSales = new double[12], totCost = new double[12], totDiff = new double[12];
        boolean[] anyBase = new boolean[12], anySales = new boolean[12], anyPnl = new boolean[12];
        for (SiteData d : data) {
            double factor;
            String source;
            boolean capped = false;
            if (overrideFactor != null) {
                factor = overrideFactor;
                source = "OVERRIDE";
            } else if (d.ownGrowth() != null) {
                factor = d.ownGrowth().factor();
                capped = d.ownGrowth().capped();
                source = "SITE";
            } else if (company != null) {
                factor = company.factor();
                capped = company.capped();
                source = "COMPANY";
            } else {
                factor = 1.0;
                source = "NONE";
            }
            KioskForecastMath.YearProjection p = KioskForecastMath.projectNextYear(
                    d.previous(), d.current(), currentMonth, factor, seasonal);
            if (java.util.Arrays.stream(p.target()).filter(java.util.Objects::nonNull).count() < 12) {
                // menos de 2 meses de historia: no hay nivel ni estacionalidad que proyectar
                skipped.add(d.site().getName());
                continue;
            }
            Reference ref = reference(cfg, d.site().getId(), YearMonth.of(baseYear, currentMonth));
            List<KioskFinancialsForecastResponse.Month> months = new ArrayList<>();
            double siteBase = 0, siteSales = 0, siteCost = 0, siteDiff = 0;
            boolean sitePnl = false;
            int estimated = 0;
            for (int m = 0; m < 12; m++) {
                Double s = p.target()[m];
                KioskFinancialsForecastResponse.Month.MonthBuilder mb = KioskFinancialsForecastResponse.Month.builder()
                        .month(m + 1)
                        .baseSales(p.baseYear()[m] == null ? null : money(p.baseYear()[m]))
                        .sales(s == null ? null : money(s))
                        .estimated(p.estimated()[m]);
                if (p.baseYear()[m] != null) {
                    siteBase += p.baseYear()[m];
                    totBase[m] += p.baseYear()[m];
                    anyBase[m] = true;
                }
                if (s != null) {
                    siteSales += s;
                    totSales[m] += s;
                    anySales[m] = true;
                    if (p.estimated()[m]) {
                        estimated++;
                    }
                    if (ref != null) {
                        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(BigDecimal.valueOf(s), ref.rates(),
                                ref.fixed(), YearMonth.of(target, m + 1).lengthOfMonth(), flat,
                                // sin metas del anio siguiente: la comision se aplica (no se puede verificar el 70 %)
                                KioskPnlCalculator.CommissionPolicy.forPeriod(target, m + 1), null);
                        mb.totalCost(KioskPnlCalculator.round2(r.totalCost())).difference(KioskPnlCalculator.round2(r.difference()))
                                .margin(KioskPnlCalculator.round4(r.margin())).breakEven(KioskPnlCalculator.round2(r.breakEven()));
                        siteCost += r.totalCost().doubleValue();
                        siteDiff += r.difference().doubleValue();
                        totCost[m] += r.totalCost().doubleValue();
                        totDiff[m] += r.difference().doubleValue();
                        anyPnl[m] = true;
                        sitePnl = true;
                    }
                }
                months.add(mb.build());
            }
            siteYears.add(KioskFinancialsForecastResponse.SiteYear.builder()
                    .siteId(d.site().getId()).name(d.site().getName())
                    .growthFactor(BigDecimal.valueOf(factor).setScale(4, RoundingMode.HALF_UP)).growthSource(source)
                    .growthCapped(capped).baseSales(money(siteBase)).sales(money(siteSales)).estimatedMonths(estimated)
                    .costsFrom(ref == null ? null : ref.from().toString())
                    .totalCost(sitePnl ? money(siteCost) : null).difference(sitePnl ? money(siteDiff) : null)
                    .margin(sitePnl && siteSales > 0 ? ratio(siteDiff, siteSales) : null)
                    .months(months).build());
        }

        List<KioskFinancialsForecastResponse.Month> totalMonths = new ArrayList<>();
        double allBase = 0, allSales = 0, allCost = 0, allDiff = 0;
        boolean anyCost = false;
        for (int m = 0; m < 12; m++) {
            totalMonths.add(KioskFinancialsForecastResponse.Month.builder().month(m + 1)
                    .baseSales(anyBase[m] ? money(totBase[m]) : null).sales(anySales[m] ? money(totSales[m]) : null)
                    .estimated(false)
                    .totalCost(anyPnl[m] ? money(totCost[m]) : null).difference(anyPnl[m] ? money(totDiff[m]) : null)
                    .margin(anyPnl[m] && totSales[m] > 0 ? ratio(totDiff[m], totSales[m]) : null).build());
            allBase += totBase[m];
            allSales += totSales[m];
            if (anyPnl[m]) {
                allCost += totCost[m];
                allDiff += totDiff[m];
                anyCost = true;
            }
        }
        KioskFinancialsForecastResponse.SiteYear totals = KioskFinancialsForecastResponse.SiteYear.builder()
                .name("Total").baseSales(money(allBase)).sales(money(allSales))
                .totalCost(anyCost ? money(allCost) : null).difference(anyCost ? money(allDiff) : null)
                .margin(anyCost && allSales > 0 ? ratio(allDiff, allSales) : null)
                .months(totalMonths).build();

        List<BigDecimal> idx = new ArrayList<>();
        for (double v : seasonal) {
            idx.add(BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP));
        }
        return KioskFinancialsForecastResponse.NextYear.builder()
                .asOf(today).baseYear(baseYear).targetYear(target)
                .growthMode(overrideFactor != null ? "OVERRIDE" : "SITE_OR_COMPANY")
                .companyGrowthFactor(company == null ? null : BigDecimal.valueOf(company.factor()).setScale(4, RoundingMode.HALF_UP))
                .overrideGrowthPct(growthPct)
                .breakEvenMode(beMode).seasonalIndex(idx).sites(siteYears).skippedSites(skipped).totals(totals).build();
    }

    // ------------------------------------------------------------------ utilidades

    private static LocalDate resolveToday(String asOf) throws BusinessException {
        if (asOf == null || asOf.isBlank()) {
            return GuatemalaDateTime.today();
        }
        try {
            return LocalDate.parse(asOf.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("asOf inválido: '" + asOf + "' (use yyyy-MM-dd).");
        }
    }

    /** Sitios que entran a los reportes (sin externos), filtrados por {@code siteIds} si vienen. */
    private List<KioskSiteEntity> selectSites(String siteIdsCsv) throws BusinessException {
        Set<Long> requested = KioskFinancialsReportService.parseSiteIds(siteIdsCsv);
        List<KioskSiteEntity> all = siteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
        if (requested.isEmpty()) {
            return all;
        }
        List<KioskSiteEntity> selected = all.stream().filter(s -> requested.contains(s.getId())).collect(Collectors.toList());
        Set<Long> found = selected.stream().map(KioskSiteEntity::getId).collect(Collectors.toSet());
        for (Long id : requested) {
            if (!found.contains(id)) {
                throw new BusinessException("Sitio no encontrado: " + id);
            }
        }
        return selected;
    }

    /** Activo = no cerrado y con ventas en las últimas 4 semanas (los cerrados o dormidos no se proyectan). */
    private static boolean isActive(KioskSiteEntity site, SiteSales ss, LocalDate today) {
        if (ss == null || KioskSiteEntity.STATUS_CLOSED.equals(site.getStatus())) {
            return false;
        }
        LocalDate from = today.minusDays(ACTIVITY_DAYS);
        return ss.daily().subMap(from, true, today, true).values().stream().anyMatch(v -> v != null && v.signum() > 0);
    }

    /** Ventas por mes (null si el mes no tiene ventas); sólo los primeros {@code months} meses. */
    private static Double[] monthlySums(NavigableMap<LocalDate, BigDecimal> daily, int year, int months) {
        Double[] out = new Double[12];
        for (int m = 1; m <= months; m++) {
            YearMonth ym = YearMonth.of(year, m);
            double sum = 0;
            boolean any = false;
            for (BigDecimal v : daily.subMap(ym.atDay(1), true, ym.atEndOfMonth(), true).values()) {
                if (v != null && v.signum() > 0) {
                    sum += v.doubleValue();
                    any = true;
                }
            }
            out[m - 1] = any ? sum : null;
        }
        return out;
    }

    private Config loadConfig(int year) {
        Config c = new Config();
        for (int y = year - 1; y <= year; y++) {
            for (KioskPeriodConfigEntity e : configRepository.findByPeriodYear(y)) {
                c.configs.put(Config.key(e.getSiteId(), e.getPeriodYear(), e.getPeriodMonth()), e);
            }
            for (KioskFixedCostEntity fc : fixedCostRepository.findByPeriodYear(y)) {
                c.costs.computeIfAbsent(Config.key(fc.getSiteId(), fc.getPeriodYear(), fc.getPeriodMonth()), k -> new HashMap<>())
                        .put(fc.getCategoryCode(), fc.getAmount());
            }
        }
        return c;
    }

    /** Último mes (hasta {@code upTo}, con 12 meses de margen) con las cuatro tasas configuradas. */
    private static Reference reference(Config cfg, Long siteId, YearMonth upTo) {
        YearMonth ym = upTo;
        for (int i = 0; i < COST_LOOKBACK_MONTHS; i++, ym = ym.minusMonths(1)) {
            KioskPeriodConfigEntity e = cfg.configs.get(Config.key(siteId, ym.getYear(), ym.getMonthValue()));
            if (e == null) {
                continue;
            }
            KioskPnlCalculator.Rates rates = new KioskPnlCalculator.Rates(e.getProductCostPct(), e.getSalesCommissionPct(),
                    e.getCardCommissionPct(), e.getTaxPct());
            if (rates.complete()) {
                return new Reference(ym, rates, cfg.costs.getOrDefault(
                        Config.key(siteId, ym.getYear(), ym.getMonthValue()), Map.of()));
            }
        }
        return null;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(double num, double den) {
        return den == 0 ? null : BigDecimal.valueOf(num / den).setScale(4, RoundingMode.HALF_UP);
    }
}
