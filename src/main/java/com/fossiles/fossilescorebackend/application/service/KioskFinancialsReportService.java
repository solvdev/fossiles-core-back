package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCompareResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCompletenessResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsDailyMatrixResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsPnlResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsSupervisorsResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.application.util.KioskEffectiveGoals;
import com.fossiles.fossilescorebackend.application.util.KioskPnlCalculator;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCostCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPeriodConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskCostCategoryRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reportes de finanzas por kiosco: P&amp;L, matriz diaria, comparativo entre anios y completitud.
 * Las ventas vienen de {@link KioskSalesSourceResolver}; costos/tasas/metas de la configuracion mensual.
 */
@Service
@RequiredArgsConstructor
public class KioskFinancialsReportService {

    public static final String MODE_SAME_PERIOD = "SAME_PERIOD";
    public static final String MODE_FULL_MONTH = "FULL_MONTH";
    /** Punto de equilibrio con las tasas reales de cada kiosco (por defecto) o con la tasa fija de los Excel recientes. */
    public static final String BREAK_EVEN_RATES = "RATES";
    public static final String BREAK_EVEN_FLAT = "FLAT";

    private final KioskFinancialsAccessGuard guard;
    private final KioskSiteRepository siteRepository;
    private final KioskCostCategoryRepository categoryRepository;
    private final KioskFixedCostRepository fixedCostRepository;
    private final KioskPeriodConfigRepository configRepository;
    private final KioskSalesSourceResolver salesSourceResolver;
    private final KioskGoalModuleReader goalReader;
    private final KioskSupervisorAssignmentReader supervisorReader;
    private final KioskFinancialsSettingsService settingsService;

    private record PeriodKey(Long siteId, int year, int month) {
    }

    /** Configuracion cargada de un anio. */
    private static final class YearData {
        final Map<PeriodKey, KioskPeriodConfigEntity> configs = new HashMap<>();
        final Map<PeriodKey, Map<String, BigDecimal>> costs = new HashMap<>();
        /** Meta efectiva: modulo Metas de kioscos primero, configuracion de Finanzas como respaldo. */
        KioskEffectiveGoals goals = KioskEffectiveGoals.empty();
    }

    /** Resultado de un mes (o fraccion de mes) de un sitio. */
    private record MonthCalc(KioskPnlCalculator.Result result, Map<String, BigDecimal> fixedByCategory,
                             BigDecimal goal, boolean complete) {
    }

    // ------------------------------------------------------------------ P&L

    @Transactional(readOnly = true)
    public KioskFinancialsPnlResponse getPnl(Integer year, Integer month, String siteIdsCsv) throws BusinessException {
        // Método vigente en la configuración (Costos por kiosco): aplica a todos los reportes y descargas
        return getPnl(year, month, siteIdsCsv, settingsService.breakEvenMode());
    }

    /** @param breakEvenMode RATES (por defecto) o FLAT: sólo cambia el punto de equilibrio, no costos ni utilidad. */
    @Transactional(readOnly = true)
    public KioskFinancialsPnlResponse getPnl(Integer year, Integer month, String siteIdsCsv, String breakEvenMode)
            throws BusinessException {
        guard.assertCanView();
        final String beMode = normalizeBreakEvenMode(breakEvenMode);
        final BigDecimal flatRate = BREAK_EVEN_FLAT.equals(beMode) ? KioskPnlCalculator.FLAT_VARIABLE_RATE : null;
        int y = requireYear(year);
        if (month != null) {
            requireMonth(month);
        }
        Set<Long> requested = parseSiteIds(siteIdsCsv);
        List<KioskSiteEntity> selected = selectSites(requested);

        LocalDate from = month == null ? LocalDate.of(y, 1, 1) : LocalDate.of(y, month, 1);
        LocalDate to = month == null ? LocalDate.of(y, 12, 31) : YearMonth.of(y, month).atEndOfMonth();

        Map<Long, LocalDate> goLive = salesSourceResolver.goLiveEffective(selected);
        Map<Long, SiteSales> sales = salesSourceResolver.resolve(selected, from, to, goLive);
        List<KioskCostCategoryEntity> categories = categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc();
        List<String> requiredCodes = categories.stream().map(KioskCostCategoryEntity::getCode).toList();
        Map<String, Integer> categoryOrder = categoryOrder(categories);
        YearData yd = loadYear(y);

        Map<Long, Acc> accBySite = new LinkedHashMap<>();
        Acc total = new Acc();
        for (KioskSiteEntity site : selected) {
            SiteSales ss = sales.get(site.getId());
            if (requested.isEmpty() && (ss == null || !ss.hasData())) {
                continue;
            }
            Acc acc = new Acc();
            if (ss != null) {
                for (int m = (month == null ? 1 : month); m <= (month == null ? 12 : month); m++) {
                    LocalDate mFrom = LocalDate.of(y, m, 1);
                    LocalDate mTo = YearMonth.of(y, m).atEndOfMonth();
                    BigDecimal monthSales = ss.totalBetween(mFrom, mTo);
                    boolean include = month != null || monthSales.signum() > 0;
                    if (!include) {
                        continue;
                    }
                    MonthCalc mc = calcMonth(yd, site.getId(), y, m, monthSales, BigDecimal.ONE, requiredCodes,
                            mTo.getDayOfMonth(), flatRate);
                    Acc monthAcc = Acc.ofMonth(m, mc, mTo.getDayOfMonth(), ss.daily().subMap(mFrom, true, mTo, true));
                    acc.add(monthAcc);
                }
            }
            acc.source = ss == null ? KioskSalesSourceResolver.SOURCE_HIST : ss.source();
            accBySite.put(site.getId(), acc);
            total.add(acc);
        }

        BigDecimal grandSales = total.sales;
        List<KioskFinancialsPnlResponse.SitePnl> siteDtos = new ArrayList<>();
        for (KioskSiteEntity site : selected) {
            Acc acc = accBySite.get(site.getId());
            if (acc == null) {
                continue;
            }
            KioskFinancialsPnlResponse.SitePnl dto = KioskFinancialsPnlResponse.SitePnl.builder()
                    .siteId(site.getId())
                    .name(site.getName())
                    .source(acc.source)
                    .complete(acc.monthDays.isEmpty() ? Boolean.FALSE : acc.allComplete)
                    .build();
            fillFigures(dto, acc, grandSales, month == null, categoryOrder);
            siteDtos.add(dto);
        }
        KioskFinancialsPnlResponse.Figures totals = KioskFinancialsPnlResponse.Figures.builder().build();
        fillFigures(totals, total, grandSales, month == null, categoryOrder);

        return KioskFinancialsPnlResponse.builder().year(y).month(month).breakEvenMode(beMode)
                .sites(siteDtos).totals(totals).build();
    }

    private static String normalizeBreakEvenMode(String mode) throws BusinessException {
        if (mode == null || mode.isBlank()) {
            return BREAK_EVEN_RATES;
        }
        String m = mode.trim().toUpperCase(Locale.ROOT);
        if (!BREAK_EVEN_RATES.equals(m) && !BREAK_EVEN_FLAT.equals(m)) {
            throw new BusinessException("breakEvenMode inválido: '" + mode + "' (use RATES o FLAT).");
        }
        return m;
    }

    private static void fillFigures(KioskFinancialsPnlResponse.Figures f, Acc a, BigDecimal grandSales,
                                    boolean includeByMonth, Map<String, Integer> categoryOrder) {
        int days = a.days();
        BigDecimal daysTotal = BigDecimal.valueOf(days);
        f.setSales(KioskPnlCalculator.round2(a.sales));
        f.setGoal(KioskPnlCalculator.round2(a.goal));
        f.setGoalPct(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(a.sales, a.goal)));
        f.setParticipationPct(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(a.sales, grandSales)));
        f.setVariable(KioskFinancialsPnlResponse.Variable.builder()
                .productCost(KioskPnlCalculator.round2(a.productCost))
                .salesCommission(KioskPnlCalculator.round2(a.salesCommission))
                .cardCommission(KioskPnlCalculator.round2(a.cardCommission))
                .tax(KioskPnlCalculator.round2(a.tax))
                .total(KioskPnlCalculator.round2(a.variableTotal))
                .build());
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        a.fixedByCategory.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal> e) ->
                        categoryOrder.getOrDefault(e.getKey(), Integer.MAX_VALUE)).thenComparing(Map.Entry::getKey))
                .forEach(e -> byCategory.put(e.getKey(), KioskPnlCalculator.round2(e.getValue())));
        f.setFixed(KioskFinancialsPnlResponse.Fixed.builder()
                .byCategory(byCategory).total(KioskPnlCalculator.round2(a.fixedTotal)).build());
        f.setTotalCost(KioskPnlCalculator.round2(a.totalCost));
        f.setDifference(KioskPnlCalculator.round2(a.difference));
        f.setMargin(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(a.difference, a.sales)));
        f.setBreakEven(KioskPnlCalculator.round2(a.breakEven));
        f.setBreakEvenDaily(a.breakEven == null || days <= 0 ? null
                : KioskPnlCalculator.round2(KioskPnlCalculator.ratio(a.breakEven, daysTotal)));
        f.setDaysWithSales(a.salesDates.size());
        if (includeByMonth) {
            List<KioskFinancialsPnlResponse.MonthLine> lines = new ArrayList<>();
            for (Map.Entry<Integer, Acc> e : a.byMonth.entrySet()) {
                Acc m = e.getValue();
                lines.add(KioskFinancialsPnlResponse.MonthLine.builder()
                        .month(e.getKey())
                        .sales(KioskPnlCalculator.round2(m.sales))
                        .totalCost(KioskPnlCalculator.round2(m.totalCost))
                        .difference(KioskPnlCalculator.round2(m.difference))
                        .margin(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(m.difference, m.sales)))
                        .build());
            }
            f.setByMonth(lines);
        }
    }

    /** Acumulador de cifras de P&amp;L (sin redondear). */
    private static final class Acc {
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal productCost = BigDecimal.ZERO;
        BigDecimal salesCommission = BigDecimal.ZERO;
        BigDecimal cardCommission = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal variableTotal = BigDecimal.ZERO;
        BigDecimal fixedTotal = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal difference = BigDecimal.ZERO;
        BigDecimal breakEven; // null si ningun mes lo pudo calcular
        BigDecimal goal;      // null si ningun mes tiene meta
        boolean allComplete = true;
        String source;
        final Map<String, BigDecimal> fixedByCategory = new LinkedHashMap<>();
        /** mes -> dias del mes incluidos en el periodo (base de PE diario). */
        final Map<Integer, Integer> monthDays = new TreeMap<>();
        final Set<LocalDate> salesDates = new TreeSet<>();
        final TreeMap<Integer, Acc> byMonth = new TreeMap<>();

        static Acc ofMonth(int month, MonthCalc mc, int days, Map<LocalDate, BigDecimal> dailySales) {
            Acc a = new Acc();
            KioskPnlCalculator.Result r = mc.result();
            a.sales = r.sales();
            a.productCost = r.productCost();
            a.salesCommission = r.salesCommission();
            a.cardCommission = r.cardCommission();
            a.tax = r.tax();
            a.variableTotal = r.variableTotal();
            a.fixedTotal = r.fixedTotal();
            a.totalCost = r.totalCost();
            a.difference = r.difference();
            a.breakEven = r.breakEven();
            a.goal = mc.goal();
            a.allComplete = mc.complete();
            a.fixedByCategory.putAll(mc.fixedByCategory());
            a.monthDays.put(month, days);
            dailySales.forEach((d, v) -> {
                if (v != null && v.signum() > 0) {
                    a.salesDates.add(d);
                }
            });
            a.byMonth.put(month, a.copyFigures());
            return a;
        }

        Acc copyFigures() {
            Acc c = new Acc();
            c.sales = sales;
            c.totalCost = totalCost;
            c.difference = difference;
            return c;
        }

        void add(Acc o) {
            sales = sales.add(o.sales);
            productCost = productCost.add(o.productCost);
            salesCommission = salesCommission.add(o.salesCommission);
            cardCommission = cardCommission.add(o.cardCommission);
            tax = tax.add(o.tax);
            variableTotal = variableTotal.add(o.variableTotal);
            fixedTotal = fixedTotal.add(o.fixedTotal);
            totalCost = totalCost.add(o.totalCost);
            difference = difference.add(o.difference);
            if (o.breakEven != null) {
                breakEven = breakEven == null ? o.breakEven : breakEven.add(o.breakEven);
            }
            if (o.goal != null) {
                goal = goal == null ? o.goal : goal.add(o.goal);
            }
            o.fixedByCategory.forEach((k, v) -> fixedByCategory.merge(k, v, BigDecimal::add));
            // dias del periodo: union de meses incluidos
            o.monthDays.forEach(monthDays::putIfAbsent);
            salesDates.addAll(o.salesDates);
            allComplete = allComplete && o.allComplete;
            o.byMonth.forEach((m, figs) -> {
                Acc target = byMonth.computeIfAbsent(m, k -> new Acc());
                target.sales = target.sales.add(figs.sales);
                target.totalCost = target.totalCost.add(figs.totalCost);
                target.difference = target.difference.add(figs.difference);
            });
        }

        int days() {
            int total = 0;
            for (int d : monthDays.values()) {
                total += d;
            }
            return total;
        }
    }

    // ------------------------------------------------------------------ Matriz diaria

    @Transactional(readOnly = true)
    public KioskFinancialsDailyMatrixResponse getDailyMatrix(Integer year, Integer month, String siteIdsCsv)
            throws BusinessException {
        guard.assertCanView();
        int y = requireYear(year);
        int m = requireMonth(month);
        Set<Long> requested = parseSiteIds(siteIdsCsv);
        List<KioskSiteEntity> selected = selectSites(requested);
        YearMonth ym = YearMonth.of(y, m);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        Map<Long, LocalDate> goLive = salesSourceResolver.goLiveEffective(selected);
        Map<Long, SiteSales> sales = salesSourceResolver.resolve(selected, from, to, goLive);

        List<KioskSiteEntity> included = selected.stream()
                .filter(s -> !requested.isEmpty() || (sales.get(s.getId()) != null && sales.get(s.getId()).hasData()))
                .toList();

        List<KioskFinancialsDailyMatrixResponse.Day> days = new ArrayList<>();
        Map<Long, BigDecimal> siteTotals = new LinkedHashMap<>();
        included.forEach(s -> siteTotals.put(s.getId(), BigDecimal.ZERO));
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal grand = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<Long, BigDecimal> values = new LinkedHashMap<>();
            BigDecimal dayTotal = BigDecimal.ZERO;
            for (KioskSiteEntity s : included) {
                SiteSales ss = sales.get(s.getId());
                BigDecimal v = ss == null ? null : ss.daily().get(d);
                values.put(s.getId(), KioskPnlCalculator.round2(v));
                if (v != null) {
                    dayTotal = dayTotal.add(v);
                    siteTotals.merge(s.getId(), v, BigDecimal::add);
                }
            }
            cumulative = cumulative.add(dayTotal);
            grand = grand.add(dayTotal);
            days.add(KioskFinancialsDailyMatrixResponse.Day.builder()
                    .date(d).values(values)
                    .total(KioskPnlCalculator.round2(dayTotal))
                    .cumulative(KioskPnlCalculator.round2(cumulative))
                    .build());
        }
        siteTotals.replaceAll((k, v) -> KioskPnlCalculator.round2(v));

        return KioskFinancialsDailyMatrixResponse.builder()
                .year(y).month(m)
                .sites(included.stream().map(s -> KioskFinancialsDailyMatrixResponse.SiteRef.builder()
                        .siteId(s.getId()).name(s.getName()).build()).toList())
                .days(days)
                .siteTotals(siteTotals)
                .grandTotal(KioskPnlCalculator.round2(grand))
                .build();
    }

    // ------------------------------------------------------------------ Comparativo

    @Transactional(readOnly = true)
    public KioskFinancialsCompareResponse compare(Integer year, Integer baseYear, Integer fromMonth, Integer toMonth,
                                                  String mode, String siteIdsCsv) throws BusinessException {
        return compare(year, baseYear, fromMonth, toMonth, mode, siteIdsCsv, GuatemalaDateTime.today());
    }

    /** Variante con fecha "hoy" explicita (pruebas). */
    KioskFinancialsCompareResponse compare(Integer year, Integer baseYear, Integer fromMonth, Integer toMonth,
                                           String mode, String siteIdsCsv, LocalDate asOf) throws BusinessException {
        guard.assertCanView();
        int y = requireYear(year);
        int by = requireYear(baseYear);
        int fm = fromMonth == null ? 1 : requireMonth(fromMonth);
        int tm = toMonth == null ? 12 : requireMonth(toMonth);
        if (fm > tm) {
            throw new BusinessException("El mes inicial no puede ser mayor al mes final.");
        }
        String effectiveMode = mode == null || mode.isBlank() ? MODE_SAME_PERIOD : mode.trim().toUpperCase(Locale.ROOT);
        if (!MODE_SAME_PERIOD.equals(effectiveMode) && !MODE_FULL_MONTH.equals(effectiveMode)) {
            throw new BusinessException("Modo inválido '" + mode + "'. Use SAME_PERIOD o FULL_MONTH.");
        }
        boolean samePeriod = MODE_SAME_PERIOD.equals(effectiveMode);
        Set<Long> requested = parseSiteIds(siteIdsCsv);
        List<KioskSiteEntity> selected = selectSites(requested);

        LocalDate rangeStart = LocalDate.of(y, fm, 1);
        LocalDate rangeEnd = YearMonth.of(y, tm).atEndOfMonth();
        LocalDate baseRangeStart = LocalDate.of(by, fm, 1);
        LocalDate baseRangeEnd = YearMonth.of(by, tm).atEndOfMonth();

        Map<Long, LocalDate> goLive = salesSourceResolver.goLiveEffective(selected);
        Map<Long, SiteSales> sales = salesSourceResolver.resolve(selected, rangeStart, rangeEnd, goLive);
        Map<Long, SiteSales> baseSales = salesSourceResolver.resolve(selected, baseRangeStart, baseRangeEnd, goLive);

        List<String> requiredCodes = categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(KioskCostCategoryEntity::getCode)
                .filter(code -> !KioskExcelParser.OPTIONAL_COST_CODES.contains(code)).toList();
        YearData yd = loadYear(y);
        YearData ybd = by == y ? yd : loadYear(by);

        List<KioskFinancialsCompareResponse.SiteCompare> siteDtos = new ArrayList<>();
        BigDecimal tSales = BigDecimal.ZERO, tBaseSales = BigDecimal.ZERO;
        BigDecimal tDiff = BigDecimal.ZERO, tBaseDiff = BigDecimal.ZERO;
        BigDecimal[] mSales = new BigDecimal[13], mBaseSales = new BigDecimal[13];
        BigDecimal[] mCost = new BigDecimal[13], mBaseCost = new BigDecimal[13];
        for (int m = 1; m <= 12; m++) {
            mSales[m] = BigDecimal.ZERO;
            mBaseSales[m] = BigDecimal.ZERO;
            mCost[m] = BigDecimal.ZERO;
            mBaseCost[m] = BigDecimal.ZERO;
        }

        for (KioskSiteEntity site : selected) {
            LocalDate periodFrom;
            LocalDate periodTo;
            if (samePeriod) {
                LocalDate live = goLive.get(site.getId());
                periodFrom = live != null && live.isAfter(rangeStart) ? live : rangeStart;
                periodTo = asOf.isBefore(rangeEnd) ? asOf : rangeEnd;
            } else {
                periodFrom = rangeStart;
                periodTo = rangeEnd;
            }
            boolean validPeriod = !periodFrom.isAfter(periodTo);
            SiteSales ss = sales.get(site.getId());
            SiteSales bs = baseSales.get(site.getId());

            BigDecimal sSales = BigDecimal.ZERO, sBaseSales = BigDecimal.ZERO;
            BigDecimal sCost = BigDecimal.ZERO, sBaseCost = BigDecimal.ZERO;
            BigDecimal sGoal = null, sBaseGoal = null;
            LocalDate basePeriodFrom = null, basePeriodTo = null;

            if (validPeriod) {
                basePeriodFrom = samePeriod ? alignToBaseYear(periodFrom, by) : baseRangeStart;
                basePeriodTo = samePeriod ? alignToBaseYear(periodTo, by) : baseRangeEnd;
                for (int m = fm; m <= tm; m++) {
                    YearMonth ym = YearMonth.of(y, m);
                    LocalDate sliceFrom = periodFrom.isAfter(ym.atDay(1)) ? periodFrom : ym.atDay(1);
                    LocalDate sliceTo = periodTo.isBefore(ym.atEndOfMonth()) ? periodTo : ym.atEndOfMonth();
                    if (sliceFrom.isAfter(sliceTo)) {
                        continue;
                    }
                    YearMonth bym = YearMonth.of(by, m);
                    LocalDate baseSliceFrom = samePeriod ? alignToBaseYear(sliceFrom, by) : bym.atDay(1);
                    LocalDate baseSliceTo = samePeriod ? alignToBaseYear(sliceTo, by) : bym.atEndOfMonth();

                    BigDecimal cur = ss == null ? BigDecimal.ZERO : ss.totalBetween(sliceFrom, sliceTo);
                    BigDecimal base = bs == null || baseSliceFrom.isAfter(baseSliceTo)
                            ? BigDecimal.ZERO : bs.totalBetween(baseSliceFrom, baseSliceTo);
                    if (cur.signum() == 0 && base.signum() == 0) {
                        continue; // sin ventas en ninguno de los dos anios: no se cargan costos fijos
                    }

                    BigDecimal curFactor = dayFactor(ChronoUnit.DAYS.between(sliceFrom, sliceTo) + 1, ym.lengthOfMonth());
                    long baseDays = ChronoUnit.DAYS.between(baseSliceFrom, baseSliceTo) + 1;
                    BigDecimal baseFactor = dayFactor(Math.max(baseDays, 0), bym.lengthOfMonth());

                    MonthCalc c = calcMonth(yd, site.getId(), y, m, cur, curFactor, requiredCodes, ym.lengthOfMonth(), null);
                    MonthCalc b = calcMonth(ybd, site.getId(), by, m, base, baseFactor, requiredCodes, bym.lengthOfMonth(), null);

                    sSales = sSales.add(cur);
                    sBaseSales = sBaseSales.add(base);
                    sCost = sCost.add(c.result().totalCost());
                    sBaseCost = sBaseCost.add(b.result().totalCost());
                    if (c.goal() != null) {
                        sGoal = sGoal == null ? c.goal() : sGoal.add(c.goal());
                    }
                    if (b.goal() != null) {
                        sBaseGoal = sBaseGoal == null ? b.goal() : sBaseGoal.add(b.goal());
                    }
                    mSales[m] = mSales[m].add(cur);
                    mBaseSales[m] = mBaseSales[m].add(base);
                    mCost[m] = mCost[m].add(c.result().totalCost());
                    mBaseCost[m] = mBaseCost[m].add(b.result().totalCost());
                }
            }

            if (requested.isEmpty() && sSales.signum() == 0 && sBaseSales.signum() == 0) {
                continue;
            }
            BigDecimal diff = sSales.subtract(sCost);
            BigDecimal baseDiff = sBaseSales.subtract(sBaseCost);
            BigDecimal delta = sSales.subtract(sBaseSales);
            tSales = tSales.add(sSales);
            tBaseSales = tBaseSales.add(sBaseSales);
            tDiff = tDiff.add(diff);
            tBaseDiff = tBaseDiff.add(baseDiff);
            siteDtos.add(KioskFinancialsCompareResponse.SiteCompare.builder()
                    .siteId(site.getId()).name(site.getName())
                    .periodFrom(validPeriod ? periodFrom : null)
                    .periodTo(validPeriod ? periodTo : null)
                    .basePeriodFrom(basePeriodFrom)
                    .basePeriodTo(basePeriodTo)
                    .sales(KioskPnlCalculator.round2(sSales))
                    .baseSales(KioskPnlCalculator.round2(sBaseSales))
                    .delta(KioskPnlCalculator.round2(delta))
                    .deltaPct(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(delta, sBaseSales)))
                    .goalPct(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(sSales, sGoal)))
                    .baseGoalPct(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(sBaseSales, sBaseGoal)))
                    .margin(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(diff, sSales)))
                    .baseMargin(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(baseDiff, sBaseSales)))
                    .difference(KioskPnlCalculator.round2(diff))
                    .baseDifference(KioskPnlCalculator.round2(baseDiff))
                    .build());
        }

        BigDecimal tDelta = tSales.subtract(tBaseSales);
        KioskFinancialsCompareResponse.Totals totals = KioskFinancialsCompareResponse.Totals.builder()
                .sales(KioskPnlCalculator.round2(tSales))
                .baseSales(KioskPnlCalculator.round2(tBaseSales))
                .delta(KioskPnlCalculator.round2(tDelta))
                .deltaPct(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(tDelta, tBaseSales)))
                .margin(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(tDiff, tSales)))
                .baseMargin(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(tBaseDiff, tBaseSales)))
                .difference(KioskPnlCalculator.round2(tDiff))
                .baseDifference(KioskPnlCalculator.round2(tBaseDiff))
                .build();

        // Mensual: acumulado de los sitios evaluados (los excluidos en modo automatico no aportan: 0 ventas y 0 costos).
        List<KioskFinancialsCompareResponse.MonthCompare> monthly = new ArrayList<>();
        for (int m = fm; m <= tm; m++) {
            monthly.add(KioskFinancialsCompareResponse.MonthCompare.builder()
                    .month(m)
                    .sales(KioskPnlCalculator.round2(mSales[m]))
                    .baseSales(KioskPnlCalculator.round2(mBaseSales[m]))
                    .totalCost(KioskPnlCalculator.round2(mCost[m]))
                    .baseTotalCost(KioskPnlCalculator.round2(mBaseCost[m]))
                    .margin(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(mSales[m].subtract(mCost[m]), mSales[m])))
                    .baseMargin(KioskPnlCalculator.round4(
                            KioskPnlCalculator.ratio(mBaseSales[m].subtract(mBaseCost[m]), mBaseSales[m])))
                    .build());
        }

        return KioskFinancialsCompareResponse.builder()
                .year(y).baseYear(by).mode(effectiveMode).asOf(asOf)
                .sites(siteDtos).totals(totals).monthly(monthly)
                .build();
    }

    /** Misma fecha en el anio base; 29-feb pasa a 28-feb cuando el anio base no es bisiesto. */
    static LocalDate alignToBaseYear(LocalDate date, int baseYear) {
        int day = Math.min(date.getDayOfMonth(), YearMonth.of(baseYear, date.getMonthValue()).lengthOfMonth());
        return LocalDate.of(baseYear, date.getMonthValue(), day);
    }

    private static BigDecimal dayFactor(long days, int monthLength) {
        if (days >= monthLength) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(monthLength), 10, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ Completitud

    @Transactional(readOnly = true)
    public KioskFinancialsCompletenessResponse getCompleteness(Integer year) throws BusinessException {
        guard.assertCanView();
        int y = requireYear(year);
        List<KioskSiteEntity> sites = siteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
        List<String> requiredCodes = categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(KioskCostCategoryEntity::getCode)
                .filter(code -> !KioskExcelParser.OPTIONAL_COST_CODES.contains(code)).toList();
        YearData yd = loadYear(y);
        Map<Long, LocalDate> goLive = salesSourceResolver.goLiveEffective(sites);
        Map<Long, SiteSales> sales = salesSourceResolver.resolve(sites, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), goLive);

        List<KioskFinancialsCompletenessResponse.Site> result = new ArrayList<>();
        for (KioskSiteEntity site : sites) {
            SiteSales ss = sales.get(site.getId());
            List<KioskFinancialsCompletenessResponse.Month> months = new ArrayList<>();
            boolean anyTrue = false;
            for (int m = 1; m <= 12; m++) {
                YearMonth ym = YearMonth.of(y, m);
                boolean hasSales = ss != null && !ss.daily().subMap(ym.atDay(1), true, ym.atEndOfMonth(), true).isEmpty();
                PeriodKey key = new PeriodKey(site.getId(), y, m);
                Map<String, BigDecimal> costs = yd.costs.getOrDefault(key, Map.of());
                boolean hasCosts = !requiredCodes.isEmpty() && requiredCodes.stream().allMatch(c -> costs.get(c) != null);
                KioskPeriodConfigEntity cfg = yd.configs.get(key);
                boolean hasGoal = yd.goals.goal(site.getId(), m, cfg) != null;
                anyTrue |= hasSales || hasCosts || hasGoal;
                months.add(KioskFinancialsCompletenessResponse.Month.builder()
                        .month(m).hasSales(hasSales).hasCosts(hasCosts).hasGoal(hasGoal).build());
            }
            // Sitios cerrados sin ningun dato en el anio no aportan al mapa de completitud
            if (anyTrue || KioskSiteEntity.STATUS_ACTIVE.equals(site.getStatus())) {
                result.add(KioskFinancialsCompletenessResponse.Site.builder()
                        .siteId(site.getId()).name(site.getName()).months(months).build());
            }
        }
        return KioskFinancialsCompletenessResponse.builder().year(y).sites(result).build();
    }

    // ------------------------------------------------------------------ Utilidades

    private MonthCalc calcMonth(YearData yd, Long siteId, int year, int month, BigDecimal sales,
                                BigDecimal factor, List<String> requiredCodes, int daysInMonth,
                                BigDecimal flatVariableRate) {
        PeriodKey key = new PeriodKey(siteId, year, month);
        KioskPeriodConfigEntity cfg = yd.configs.get(key);
        Map<String, BigDecimal> costs = yd.costs.getOrDefault(key, Map.of());
        KioskPnlCalculator.Rates rates = cfg == null
                ? new KioskPnlCalculator.Rates(null, null, null, null)
                : new KioskPnlCalculator.Rates(cfg.getProductCostPct(), cfg.getSalesCommissionPct(),
                cfg.getCardCommissionPct(), cfg.getTaxPct());

        boolean scale = factor.compareTo(BigDecimal.ONE) != 0;
        Map<String, BigDecimal> scaled = new LinkedHashMap<>();
        costs.forEach((code, amount) -> scaled.put(code, scale && amount != null ? amount.multiply(factor) : amount));
        BigDecimal rawGoal = yd.goals.goal(siteId, month, cfg);
        BigDecimal goal = rawGoal == null ? null : (scale ? rawGoal.multiply(factor) : rawGoal);
        boolean complete = cfg != null
                && KioskPnlCalculator.isMonthComplete(rawGoal, rates, costs, requiredCodes);
        // Comision de venta: regla del anio (desde 2026: ventas x tasa y solo si % de meta >= 70 %)
        KioskPnlCalculator.Result result = KioskPnlCalculator.calculate(sales, rates, scaled, daysInMonth,
                flatVariableRate, KioskPnlCalculator.CommissionPolicy.forYear(year), goal);
        return new MonthCalc(result, scaled, goal, complete);
    }

    private YearData loadYear(int year) {
        YearData data = new YearData();
        data.goals = goalReader.forYear(year, siteRepository.findAll());
        for (KioskPeriodConfigEntity c : configRepository.findByPeriodYear(year)) {
            data.configs.put(new PeriodKey(c.getSiteId(), c.getPeriodYear(), c.getPeriodMonth()), c);
        }
        for (KioskFixedCostEntity fc : fixedCostRepository.findByPeriodYear(year)) {
            data.costs.computeIfAbsent(new PeriodKey(fc.getSiteId(), fc.getPeriodYear(), fc.getPeriodMonth()),
                    k -> new LinkedHashMap<>()).put(fc.getCategoryCode(), fc.getAmount());
        }
        return data;
    }

    private static Map<String, Integer> categoryOrder(List<KioskCostCategoryEntity> categories) {
        Map<String, Integer> order = new HashMap<>();
        for (KioskCostCategoryEntity c : categories) {
            order.put(c.getCode(), c.getSortOrder());
        }
        return order;
    }

    /**
     * Supervisoras (módulo "Supervisoras y kioscos") con los sitios de sus kioscos, para filtrar los reportes.
     * Sólo sitios que entran a los reportes; {@code unassignedSiteIds} = kioscos con POS sin supervisora.
     */
    @Transactional(readOnly = true)
    public KioskFinancialsSupervisorsResponse getSupervisors() throws BusinessException {
        guard.assertCanView();
        Map<Long, Long> siteByLocation = new HashMap<>();
        for (KioskSiteEntity s : siteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc()) {
            if (s.getLocationId() != null) {
                siteByLocation.put(s.getLocationId(), s.getId());
            }
        }
        Set<Long> assigned = new HashSet<>();
        List<KioskFinancialsSupervisorsResponse.Supervisor> supervisors = new ArrayList<>();
        for (KioskSupervisorAssignmentReader.Supervisor sup : supervisorReader.supervisors()) {
            List<Long> siteIds = sup.kioskLocationIds().stream().map(siteByLocation::get).filter(Objects::nonNull)
                    .sorted().collect(Collectors.toList());
            if (siteIds.isEmpty()) {
                continue;
            }
            assigned.addAll(siteIds);
            supervisors.add(KioskFinancialsSupervisorsResponse.Supervisor.builder()
                    .userId(sup.userId()).name(sup.name()).siteIds(siteIds).build());
        }
        List<Long> unassigned = siteByLocation.values().stream().filter(id -> !assigned.contains(id)).sorted()
                .collect(Collectors.toList());
        return KioskFinancialsSupervisorsResponse.builder().supervisors(supervisors).unassignedSiteIds(unassigned).build();
    }

    private List<KioskSiteEntity> selectSites(Set<Long> requested) throws BusinessException {
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

    static Set<Long> parseSiteIds(String csv) throws BusinessException {
        Set<Long> ids = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(p));
            } catch (NumberFormatException ex) {
                throw new BusinessException("siteIds inválido: '" + p + "'.");
            }
        }
        return ids;
    }

    private static int requireYear(Integer year) throws BusinessException {
        if (year == null || year < 2000 || year > 2100) {
            throw new BusinessException("Año inválido: " + year + ".");
        }
        return year;
    }

    private static int requireMonth(Integer month) throws BusinessException {
        if (month == null || month < 1 || month > 12) {
            throw new BusinessException("Mes inválido: " + month + ". Debe estar entre 1 y 12.");
        }
        return month;
    }
}
