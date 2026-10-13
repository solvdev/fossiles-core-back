package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesConsolidatedResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesConsolidatedResponse.DailySourcePoint;
import com.fossiles.fossilescorebackend.application.dto.response.SalesConsolidatedResponse.MonthlyPoint;
import com.fossiles.fossilescorebackend.application.dto.response.SalesConsolidatedResponse.SourceSummary;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SourceKpis;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/** Vista consolidada: solo dinero y totales de las tres fuentes, sin rankings de producto mezclados. */
@Service
@RequiredArgsConstructor
public class SalesConsolidatedService {

    private final KioskSalesDashboardService kioskService;
    private final OnlineSalesDashboardService onlineService;
    private final VendorSalesDashboardService vendorService;
    private final SalesDashboardCache cache;

    public SalesConsolidatedResponse getConsolidated(
            LocalDate startDate, LocalDate endDate, boolean refresh) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        String key = SalesDashboardCache.key("CONSOLIDATED", range.from(), range.to(), null);
        // El canal KIOSKO se construye igual que la pestaña Kioskos sin filtro de sitio (misma fuente de Finanzas).
        return cache.get(key, refresh, () -> build(
                range,
                kioskService.buildAll(range),
                onlineService.build(range),
                vendorService.build(range)));
    }

    SalesConsolidatedResponse build(
            DateRange range,
            SalesSourceDetailResponse kiosko,
            SalesSourceDetailResponse online,
            SalesSourceDetailResponse vendor) {
        SourceKpis totals = sumKpis(kiosko.getKpis(), online.getKpis(), vendor.getKpis());
        DateRange previous = previousPeriod(range);

        List<SourceSummary> sources = new ArrayList<>();
        for (SalesSourceDetailResponse source : List.of(kiosko, online, vendor)) {
            sources.add(SourceSummary.builder()
                    .channel(source.getChannel())
                    .label(source.getLabel())
                    .kpis(source.getKpis())
                    .sharePercent(percent(source.getKpis().getTotalAmount(), totals.getTotalAmount()))
                    .build());
        }

        List<MonthlyPoint> monthly = new ArrayList<>();
        for (int i = 0; i < kiosko.getMonthlyTrend().size(); i++) {
            SalesSourceDetailResponse.TrendPoint k = kiosko.getMonthlyTrend().get(i);
            BigDecimal o = online.getMonthlyTrend().get(i).getAmount();
            BigDecimal v = vendor.getMonthlyTrend().get(i).getAmount();
            monthly.add(MonthlyPoint.builder()
                    .label(k.getLabel())
                    .year(k.getYear())
                    .month(k.getMonth())
                    .kiosko(k.getAmount())
                    .online(o)
                    .vendor(v)
                    .total(money(k.getAmount().add(o).add(v)))
                    .build());
        }

        List<DailySourcePoint> daily = new ArrayList<>();
        for (int i = 0; i < kiosko.getDailySeries().size(); i++) {
            SalesSourceDetailResponse.DailyPoint k = kiosko.getDailySeries().get(i);
            BigDecimal o = online.getDailySeries().get(i).getAmount();
            BigDecimal v = vendor.getDailySeries().get(i).getAmount();
            daily.add(DailySourcePoint.builder()
                    .date(k.getDate())
                    .kiosko(k.getAmount())
                    .online(o)
                    .vendor(v)
                    .total(money(k.getAmount().add(o).add(v)))
                    .build());
        }

        return SalesConsolidatedResponse.builder()
                .startDate(range.from())
                .endDate(range.to())
                .previousStartDate(previous.from())
                .previousEndDate(previous.to())
                .totals(totals)
                .sources(sources)
                .monthlyTrend(monthly)
                .dailySeries(daily)
                .build();
    }

    /**
     * Suma de las tres fuentes. El ticket promedio excluye el histórico de kioscos (no tiene tickets):
     * {@code (total - histórico) / tickets}, es decir, el promedio de lo que sí tiene ticket.
     */
    private static SourceKpis sumKpis(SourceKpis... kpis) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal product = BigDecimal.ZERO;
        BigDecimal packaging = BigDecimal.ZERO;
        BigDecimal shipping = BigDecimal.ZERO;
        BigDecimal historical = BigDecimal.ZERO;
        BigDecimal previousTotal = BigDecimal.ZERO;
        BigDecimal daily = BigDecimal.ZERO;
        BigDecimal units = BigDecimal.ZERO;
        int count = 0;
        for (SourceKpis k : kpis) {
            total = total.add(k.getTotalAmount());
            product = product.add(k.getProductAmount());
            packaging = packaging.add(k.getPackagingAmount());
            shipping = shipping.add(k.getShippingAmount());
            historical = historical.add(nz(k.getHistoricalAmount()));
            previousTotal = previousTotal.add(k.getPreviousTotalAmount());
            daily = daily.add(k.getDailyAmount());
            units = units.add(k.getUnitsFinished());
            count += k.getSalesCount();
        }
        return SourceKpis.builder()
                .totalAmount(money(total))
                .productAmount(money(product))
                .packagingAmount(money(packaging))
                .shippingAmount(money(shipping))
                .historicalAmount(money(historical))
                .previousTotalAmount(money(previousTotal))
                .growthPercent(growthPercent(total, previousTotal))
                .dailyAmount(money(daily))
                .salesCount(count)
                .unitsFinished(units)
                .avgTicket(average(total.subtract(historical), count))
                .build();
    }
}
