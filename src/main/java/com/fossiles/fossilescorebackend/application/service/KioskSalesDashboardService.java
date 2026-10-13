package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.BreakdownRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.KioskOption;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SaleRow;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SourceKpis;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskSalesSourceResolver.SiteSales;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.BreakdownAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.ProductAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.SaleFact;
import com.fossiles.fossilescorebackend.application.util.FinishedProductClassifier;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleItemRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/**
 * Dashboard de ventas de kioscos sobre la misma fuente que Finanzas kioscos.
 * <ul>
 *   <li><b>Dinero</b> (total, periodo anterior, hoy, serie diaria, tendencia de 6 meses e importe por kiosko):
 *   {@link KioskSalesSourceResolver} sobre los sitios {@code kiosk_site} incluidos en reportes (histórico antes del
 *   go-live efectivo del sitio, POS desde el go-live). Es el mismo número que muestra Finanzas kioscos e incluye empaque.</li>
 *   <li><b>Detalle</b> (tickets, unidades, producto/empaque, forma de pago, productos, ventas recientes): solo ventas POS
 *   del periodo actual de locations ligadas a un sitio incluido y con {@code saleDate >= go-live efectivo del sitio},
 *   de modo que el detalle POS es exactamente la parte POS del total de Finanzas. El periodo anterior y la tendencia
 *   ya no necesitan POS.</li>
 * </ul>
 * La parte del total sin desglose es el histórico ({@code historicalAmount}).
 */
@Service
@RequiredArgsConstructor
public class KioskSalesDashboardService {

    static final String SITE_NOT_AVAILABLE_MESSAGE = "El kiosko seleccionado no existe o no está incluido en los reportes.";

    private final SalesSourceLoader sourceLoader;
    private final KioskSaleItemRepository kioskSaleItemRepository;
    private final LocationRepository locationRepository;
    private final KioskSiteRepository kioskSiteRepository;
    private final KioskSalesSourceResolver salesSourceResolver;
    private final SalesDashboardCache cache;

    /**
     * @param siteId          sitio de Finanzas kioscos ({@code kiosk_site.id}); manda sobre {@code kioskLocationId}
     * @param kioskLocationId filtro legacy por location POS: se traduce al sitio incluido ligado a esa location
     */
    public SalesSourceDetailResponse getDashboard(
            LocalDate startDate, LocalDate endDate, Long siteId, Long kioskLocationId, boolean refresh)
            throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        String key = SalesDashboardCache.key(CHANNEL_KIOSKO, range.from(), range.to(), kioskLocationId, siteId);
        try {
            return cache.get(key, refresh, () -> {
                try {
                    return build(range, siteId, kioskLocationId);
                } catch (BusinessException ex) {
                    throw new RejectedFilter(ex);
                }
            });
        } catch (RejectedFilter rejected) {
            throw rejected.reason;
        }
    }

    /** Canal KIOSKO con filtro opcional de kiosko; un sitio inexistente o excluido de reportes es {@link BusinessException}. */
    SalesSourceDetailResponse build(DateRange range, Long siteId, Long kioskLocationId) throws BusinessException {
        List<KioskSiteEntity> included = loadIncludedSites();
        return compute(range, included, selectRequestedSite(included, siteId, kioskLocationId));
    }

    /** Canal KIOSKO sin filtro de sitio (lo usa el consolidado, que debe coincidir con la pestaña Kioskos). */
    SalesSourceDetailResponse buildAll(DateRange range) {
        return compute(range, loadIncludedSites(), null);
    }

    private List<KioskSiteEntity> loadIncludedSites() {
        return kioskSiteRepository.findAllByExcludeFromReportsFalseOrderBySortOrderAscNameAsc();
    }

    /** Sitio filtrado, o null sin filtro. {@code siteId} manda; la location legacy se busca entre los sitios incluidos. */
    private static KioskSiteEntity selectRequestedSite(
            List<KioskSiteEntity> included, Long siteId, Long kioskLocationId) throws BusinessException {
        if (siteId == null && kioskLocationId == null) {
            return null;
        }
        return included.stream()
                .filter(site -> siteId != null
                        ? siteId.equals(site.getId())
                        : kioskLocationId.equals(site.getLocationId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(SITE_NOT_AVAILABLE_MESSAGE));
    }

    private SalesSourceDetailResponse compute(
            DateRange range, List<KioskSiteEntity> included, KioskSiteEntity requested) {
        DateRange previous = previousPeriod(range);
        List<KioskSiteEntity> selected = requested != null ? List.of(requested) : included;

        // Dinero: misma fuente que Finanzas. El go-live se calcula una vez y la fuente se resuelve una vez para
        // TODOS los sitios incluidos; el filtro de kiosko se aplica en memoria (el resultado de cada sitio es
        // independiente del resto), así kioskOptions no necesita otra resolución.
        Map<Long, LocalDate> goLive = salesSourceResolver.goLiveEffective(included);
        Map<Long, SiteSales> sales = salesSourceResolver.resolve(included, loadFrom(range), range.to(), goLive);

        Map<LocalDate, BigDecimal> amountByDay = new HashMap<>(); // sitios seleccionados, todo el rango cargado
        Map<Long, BigDecimal> periodBySite = new HashMap<>(); // todos los sitios, solo el periodo actual
        for (KioskSiteEntity site : included) {
            SiteSales siteSales = sales.get(site.getId());
            if (siteSales == null) {
                continue;
            }
            boolean inSelection = requested == null || requested.getId().equals(site.getId());
            for (Map.Entry<LocalDate, BigDecimal> day : siteSales.daily().entrySet()) {
                BigDecimal amount = nz(day.getValue());
                if (inSelection) {
                    amountByDay.merge(day.getKey(), amount, BigDecimal::add);
                }
                if (range.contains(day.getKey())) {
                    periodBySite.merge(site.getId(), amount, BigDecimal::add);
                }
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal previousTotal = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, BigDecimal> day : amountByDay.entrySet()) {
            if (range.contains(day.getKey())) {
                total = total.add(day.getValue());
            } else if (previous.contains(day.getKey())) {
                previousTotal = previousTotal.add(day.getValue());
            }
        }
        LocalDate today = today();
        BigDecimal todayAmount = range.contains(today)
                ? amountByDay.getOrDefault(today, BigDecimal.ZERO)
                : BigDecimal.ZERO;

        // Detalle: solo POS del periodo actual, restringido a los sitios seleccionados y a fechas >= go-live.
        PosDetail pos = loadPosDetail(range, selected, goLive);
        SourceKpis kpis = kioskKpis(
                total, previousTotal, todayAmount, pos.product, pos.packaging, pos.units, pos.tickets);

        // byPaymentMethod es solo POS: su porcentaje es sobre la base POS (producto + empaque), no sobre el
        // total, que puede incluir histórico sin forma de pago.
        BigDecimal posBase = kpis.getProductAmount().add(kpis.getPackagingAmount());
        Map<String, List<BreakdownRow>> breakdowns = new LinkedHashMap<>();
        breakdowns.put("byKiosk", byKiosk(selected, periodBySite, pos.ticketsBySite, kpis.getTotalAmount()));
        breakdowns.put("byPaymentMethod", pos.byPayment.rows(posBase));

        List<SaleFact> moneyFacts = new ArrayList<>(amountByDay.size());
        amountByDay.forEach((day, amount) -> moneyFacts.add(SaleFact.moneyOnly(day, amount)));

        return SalesSourceDetailResponse.builder()
                .channel(CHANNEL_KIOSKO)
                .label("Kioskos")
                .startDate(range.from())
                .endDate(range.to())
                .previousStartDate(previous.from())
                .previousEndDate(previous.to())
                .kpis(kpis)
                .dailySeries(dailySeries(range, amountByDay, pos.ticketsByDay))
                .monthlyTrend(monthlyTrend(range.to(), moneyFacts))
                .topProducts(pos.topProducts.top(TOP_PRODUCTS_LIMIT))
                .recentSales(pos.rows.stream()
                        .sorted(Comparator.comparing(SaleRow::getSaleDate, Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(SaleRow::getId, Comparator.reverseOrder()))
                        .limit(RECENT_SALES_LIMIT)
                        .toList())
                .breakdowns(breakdowns)
                .kioskOptions(kioskOptions(included, periodBySite, requested))
                .build();
    }

    /**
     * Cabeceras e ítems POS del periodo actual. Solo cuentan locations ligadas a un sitio seleccionado con go-live
     * efectivo y ventas con {@code saleDate >= go-live} de ese sitio (el mismo corte que aplica la fuente de Finanzas);
     * los sitios históricos (sin location) o sin go-live no aportan detalle y no generan consultas POS.
     */
    private PosDetail loadPosDetail(DateRange range, List<KioskSiteEntity> selected, Map<Long, LocalDate> goLive) {
        PosDetail detail = new PosDetail();
        Map<Long, KioskSiteEntity> siteByLocation = new HashMap<>();
        for (KioskSiteEntity site : selected) {
            if (site.getLocationId() != null && goLive.get(site.getId()) != null) {
                siteByLocation.put(site.getLocationId(), site);
            }
        }
        if (siteByLocation.isEmpty()) {
            return detail;
        }

        List<KioskSaleHeaderRow> headers = sourceLoader.loadKioskHeaders(range.from(), range.to()).stream()
                .filter(header -> isPosDetailSale(header, siteByLocation, goLive))
                .toList();
        if (headers.isEmpty()) {
            return detail;
        }

        Set<Long> saleIds = headers.stream().map(KioskSaleHeaderRow::id).collect(Collectors.toSet());
        Map<Long, List<KioskSaleItemRow>> itemsBySale = new HashMap<>();
        for (KioskSaleItemRow item : kioskSaleItemRepository.findRowsBySaleDateBetween(range.from(), range.to())) {
            if (saleIds.contains(item.kioskSaleId())) {
                itemsBySale.computeIfAbsent(item.kioskSaleId(), id -> new ArrayList<>()).add(item);
            }
        }
        Map<Long, String> codeByProductId = resolveMissingCodes(itemsBySale.values());

        for (KioskSaleHeaderRow sale : headers) {
            KioskSiteEntity site = siteByLocation.get(sale.kioskLocationId());
            BigDecimal total = nz(sale.totalAmount());
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
                detail.topProducts.add(item.productId(), item.productCode(), item.productName(),
                        item.quantity(), item.lineTotal());
                String baseName = FinishedProductClassifier.baseProductName(item.productName());
                if (baseName != null && !baseName.isBlank() && !finishedNames.contains(baseName)) {
                    finishedNames.add(baseName);
                }
            }

            detail.tickets++;
            detail.product = detail.product.add(total.subtract(packaging));
            detail.packaging = detail.packaging.add(packaging);
            detail.units = detail.units.add(units);
            detail.ticketsByDay.merge(sale.saleDate(), 1, Integer::sum);
            detail.ticketsBySite.merge(site.getId(), 1, Integer::sum);
            detail.byPayment.add(sale.paymentMethod(), total);
            detail.rows.add(SaleRow.builder()
                    .id(sale.id())
                    .saleDate(sale.saleDate())
                    .reference(sale.saleNumber())
                    .productLabel(productLabel(finishedNames, !items.isEmpty(), "Venta kiosko"))
                    .quantity(units)
                    .totalAmount(money(total))
                    .status(sale.status())
                    .party(labelOrDefault(site.getName()))
                    .build());
        }
        return detail;
    }

    private static boolean isPosDetailSale(
            KioskSaleHeaderRow header, Map<Long, KioskSiteEntity> siteByLocation, Map<Long, LocalDate> goLive) {
        KioskSiteEntity site = siteByLocation.get(header.kioskLocationId());
        if (site == null || header.saleDate() == null) {
            return false;
        }
        return !header.saleDate().isBefore(goLive.get(site.getId()));
    }

    /**
     * Una fila por sitio seleccionado con venta distinta de cero en el periodo (importe de la fuente de Finanzas),
     * con la categoría de ventas A/B/C del sitio (null = sin clasificar).
     */
    private static List<BreakdownRow> byKiosk(
            List<KioskSiteEntity> selected,
            Map<Long, BigDecimal> periodBySite,
            Map<Long, Integer> ticketsBySite,
            BigDecimal totalAmount) {
        return selected.stream()
                .filter(site -> nz(periodBySite.get(site.getId())).signum() != 0)
                .map(site -> {
                    BigDecimal amount = periodBySite.get(site.getId());
                    return BreakdownRow.builder()
                            .key(String.valueOf(site.getId()))
                            .label(labelOrDefault(site.getName()))
                            .count(ticketsBySite.getOrDefault(site.getId(), 0))
                            .amount(money(amount))
                            .sharePercent(percent(amount, totalAmount))
                            .category(normalizeSiteCategory(site.getSalesCategory()))
                            .build();
                })
                .sorted(Comparator.comparing(BreakdownRow::getAmount, Comparator.reverseOrder())
                        .thenComparing(BreakdownRow::getLabel, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Sitios con venta distinta de cero en el periodo, más el sitio pedido aunque no tenga venta. No aplica el
     * filtro de kiosko (es la lista del selector) y sale ordenada por nombre. Cada opción lleva la categoría de
     * ventas A/B/C del sitio (null = sin clasificar).
     */
    private List<KioskOption> kioskOptions(
            List<KioskSiteEntity> included, Map<Long, BigDecimal> periodBySite, KioskSiteEntity requested) {
        List<KioskSiteEntity> optionSites = included.stream()
                .filter(site -> nz(periodBySite.get(site.getId())).signum() != 0
                        || (requested != null && requested.getId().equals(site.getId())))
                .toList();
        Set<Long> locationIds = optionSites.stream()
                .map(KioskSiteEntity::getLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LocationEntity> locations = locationIds.isEmpty()
                ? Map.of()
                : locationRepository.findAllById(locationIds).stream()
                        .collect(Collectors.toMap(LocationEntity::getId, row -> row, (a, b) -> a));
        return optionSites.stream()
                .map(site -> KioskOption.builder()
                        .siteId(site.getId())
                        .kioskId(site.getLocationId())
                        .kioskCode(locationCode(site.getLocationId() != null ? locations.get(site.getLocationId()) : null))
                        .kioskName(labelOrDefault(site.getName()))
                        .category(normalizeSiteCategory(site.getSalesCategory()))
                        .build())
                .sorted(Comparator.comparing(KioskOption::getKioskName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(KioskOption::getSiteId))
                .toList();
    }

    private static String locationCode(LocationEntity location) {
        return location != null && location.getCode() != null ? location.getCode() : "";
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

    /** Acumulado del detalle POS del periodo actual (lo único del canal que tiene tickets, ítems y forma de pago). */
    private static final class PosDetail {
        private int tickets;
        private BigDecimal product = BigDecimal.ZERO;
        private BigDecimal packaging = BigDecimal.ZERO;
        private BigDecimal units = BigDecimal.ZERO;
        private final Map<LocalDate, Integer> ticketsByDay = new HashMap<>();
        private final Map<Long, Integer> ticketsBySite = new HashMap<>();
        private final ProductAccumulator topProducts = new ProductAccumulator();
        private final BreakdownAccumulator byPayment = new BreakdownAccumulator();
        private final List<SaleRow> rows = new ArrayList<>();
    }

    /**
     * Lleva la {@link BusinessException} de un filtro de kiosko inválido a través del {@code Supplier} del caché
     * (que solo admite excepciones no verificadas); {@link #getDashboard} la vuelve a lanzar tal cual.
     */
    private static final class RejectedFilter extends RuntimeException {
        private final BusinessException reason;

        private RejectedFilter(BusinessException reason) {
            super(reason.getMessage(), reason, false, false);
            this.reason = reason;
        }
    }
}
