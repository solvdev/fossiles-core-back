package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskDailySalesHistEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskDailySalesHistRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosSalesAggregateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Fuente unificada de ventas diarias por sitio.
 * <p>
 * goLiveEffective = pos_go_live_override ?? MIN(sale_date) de ventas reales del kiosco (POS).
 * Para una fecha d: si goLiveEffective != null && d &gt;= goLiveEffective -&gt; POS (SUM(total_amount));
 * si no -&gt; kiosk_daily_sales_hist. Filas hist con fecha &gt;= goLive se ignoran (evita doble conteo).
 * El corte solo aplica a sitios ligados a un kiosco real (location_id no nulo).
 */
@Component
@RequiredArgsConstructor
public class KioskSalesSourceResolver {

    public static final String SOURCE_HIST = "HIST";
    public static final String SOURCE_POS = "POS";
    public static final String SOURCE_MIXED = "MIXED";

    private final KioskPosSalesAggregateRepository posRepository;
    private final KioskDailySalesHistRepository histRepository;

    /** Ventas resueltas de un sitio en un rango. */
    public record SiteSales(Long siteId, LocalDate goLive, NavigableMap<LocalDate, BigDecimal> daily,
                            boolean hasPos, boolean hasHist) {

        public String source() {
            if (hasPos && hasHist) {
                return SOURCE_MIXED;
            }
            return hasPos ? SOURCE_POS : SOURCE_HIST;
        }

        public BigDecimal total() {
            return totalBetween(null, null);
        }

        /** Suma inclusiva en [from, to]; null = sin limite. */
        public BigDecimal totalBetween(LocalDate from, LocalDate to) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<LocalDate, BigDecimal> e : daily.entrySet()) {
                if ((from == null || !e.getKey().isBefore(from)) && (to == null || !e.getKey().isAfter(to))) {
                    sum = sum.add(e.getValue());
                }
            }
            return sum;
        }

        public boolean hasData() {
            return !daily.isEmpty();
        }
    }

    /** MIN(sale_date) de ventas reales por sitio (solo sitios con location_id). */
    public Map<Long, LocalDate> detectedGoLive(Collection<KioskSiteEntity> sites) {
        Map<Long, Long> siteByLocation = new HashMap<>();
        for (KioskSiteEntity site : sites) {
            if (site.getLocationId() != null) {
                siteByLocation.put(site.getLocationId(), site.getId());
            }
        }
        Map<Long, LocalDate> result = new HashMap<>();
        if (siteByLocation.isEmpty()) {
            return result;
        }
        for (Object[] row : posRepository.findFirstRealSaleDateByLocation(siteByLocation.keySet())) {
            Long siteId = siteByLocation.get(toLong(row[0]));
            LocalDate date = toLocalDate(row[1]);
            if (siteId != null && date != null) {
                result.put(siteId, date);
            }
        }
        return result;
    }

    /** goLiveEffective por sitio (override ?? detectado); sitios sin dato no aparecen en el mapa. */
    public Map<Long, LocalDate> goLiveEffective(Collection<KioskSiteEntity> sites) {
        return goLiveEffective(sites, detectedGoLive(sites));
    }

    public Map<Long, LocalDate> goLiveEffective(Collection<KioskSiteEntity> sites, Map<Long, LocalDate> detected) {
        Map<Long, LocalDate> result = new HashMap<>();
        for (KioskSiteEntity site : sites) {
            LocalDate effective = site.getPosGoLiveOverride() != null
                    ? site.getPosGoLiveOverride()
                    : detected.get(site.getId());
            if (effective != null) {
                result.put(site.getId(), effective);
            }
        }
        return result;
    }

    /** Ventas diarias por sitio en [from, to] (inclusivo). Todos los sitios recibidos aparecen en el mapa. */
    public Map<Long, SiteSales> resolve(Collection<KioskSiteEntity> sites, LocalDate from, LocalDate to) {
        return resolve(sites, from, to, goLiveEffective(sites));
    }

    public Map<Long, SiteSales> resolve(Collection<KioskSiteEntity> sites, LocalDate from, LocalDate to,
                                        Map<Long, LocalDate> goLiveBySite) {
        Map<Long, SiteSales> result = new LinkedHashMap<>();
        if (sites.isEmpty() || from == null || to == null || to.isBefore(from)) {
            for (KioskSiteEntity site : sites) {
                result.put(site.getId(), new SiteSales(site.getId(), goLiveBySite.get(site.getId()),
                        new TreeMap<>(), false, false));
            }
            return result;
        }

        Map<Long, KioskSiteEntity> siteById = new LinkedHashMap<>();
        Map<Long, Long> siteByLocation = new HashMap<>();
        for (KioskSiteEntity site : sites) {
            siteById.put(site.getId(), site);
            if (site.getLocationId() != null) {
                siteByLocation.put(site.getLocationId(), site.getId());
            }
        }

        Map<Long, NavigableMap<LocalDate, BigDecimal>> daily = new HashMap<>();
        Map<Long, Boolean> hasPos = new HashMap<>();
        Map<Long, Boolean> hasHist = new HashMap<>();

        // Historico: solo fechas anteriores al go-live (si el sitio tiene POS)
        for (KioskDailySalesHistEntity row : histRepository.findBySitesAndRange(siteById.keySet(), from, to)) {
            KioskSiteEntity site = siteById.get(row.getSiteId());
            if (site == null || row.getAmount() == null) {
                continue;
            }
            LocalDate cutoff = posCutoff(site, goLiveBySite);
            if (cutoff != null && !row.getSaleDate().isBefore(cutoff)) {
                continue;
            }
            daily.computeIfAbsent(site.getId(), k -> new TreeMap<>()).merge(row.getSaleDate(), row.getAmount(), BigDecimal::add);
            hasHist.put(site.getId(), true);
        }

        // POS: solo fechas desde el go-live
        if (!siteByLocation.isEmpty()) {
            for (Object[] row : posRepository.sumRealSalesByLocationAndDate(siteByLocation.keySet(), from, to)) {
                Long siteId = siteByLocation.get(toLong(row[0]));
                LocalDate date = toLocalDate(row[1]);
                BigDecimal amount = row[2] == null ? null : new BigDecimal(row[2].toString());
                if (siteId == null || date == null || amount == null) {
                    continue;
                }
                LocalDate cutoff = posCutoff(siteById.get(siteId), goLiveBySite);
                if (cutoff == null || date.isBefore(cutoff)) {
                    continue;
                }
                daily.computeIfAbsent(siteId, k -> new TreeMap<>()).merge(date, amount, BigDecimal::add);
                hasPos.put(siteId, true);
            }
        }

        for (KioskSiteEntity site : sites) {
            result.put(site.getId(), new SiteSales(
                    site.getId(),
                    goLiveBySite.get(site.getId()),
                    daily.getOrDefault(site.getId(), new TreeMap<>()),
                    hasPos.getOrDefault(site.getId(), false),
                    hasHist.getOrDefault(site.getId(), false)));
        }
        return result;
    }

    private static LocalDate posCutoff(KioskSiteEntity site, Map<Long, LocalDate> goLiveBySite) {
        if (site == null || site.getLocationId() == null) {
            return null;
        }
        return goLiveBySite.get(site.getId());
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }
}
