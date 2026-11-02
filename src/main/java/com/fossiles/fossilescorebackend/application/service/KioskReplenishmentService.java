package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskReplenishmentSuggestionResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.AllocationRequest;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.Category;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.LineInput;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.LinePlan;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sugerencia de reposición de kioscos (RF-INV-05 / RF-POS-03) sin configuración manual: el mínimo y el máximo
 * de cada línea salen de la venta del kiosco en la ventana de análisis, lo que ya va en camino (envíos sin
 * recibir) se descuenta, y el disponible de Bodega PT + Devoluciones se reparte con prioridad A &gt; B &gt; C.
 * La sugerencia no mueve inventario: el usuario la confirma y se crean envíos directos a kiosko.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KioskReplenishmentService {

    static final int DEFAULT_WINDOW_DAYS = 60;
    /** Envíos sin recibir más viejos que esto se consideran abandonados y no cuentan como "en camino". */
    static final int PENDING_SHIPMENT_MAX_AGE_DAYS = 45;

    private static final String NO_HARDWARE = "NUEVO";

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductInventoryService productInventoryService;

    private record KioskRow(Long locationId, String code, String name, Category category) {
    }

    /** Acumulado de una línea de kiosco (ubicación + producto + color + talla + herraje). */
    private static final class LineAgg {
        final Long locationId;
        final Long productId;
        final Long colorId;
        final String size;
        final String hardware;
        int stock;
        int inTransit;
        BigDecimal sold = BigDecimal.ZERO;

        LineAgg(Long locationId, Long productId, Long colorId, String size, String hardware) {
            this.locationId = locationId;
            this.productId = productId;
            this.colorId = colorId;
            this.size = size;
            this.hardware = hardware;
        }

        String stockKey() {
            return productId + "|" + (colorId == null ? "" : colorId) + "|" + size;
        }
    }

    @Transactional(readOnly = true)
    public KioskReplenishmentSuggestionResponse suggest(Integer windowDays, List<Long> locationIds)
            throws BusinessException {
        int window = windowDays == null || windowDays < 7 ? DEFAULT_WINDOW_DAYS : Math.min(windowDays, 365);
        LocalDate today = GuatemalaDateTime.today();
        LocalDate since = today.minusDays(window);

        List<KioskRow> kiosks = loadKiosks();
        if (kiosks.isEmpty()) {
            return KioskReplenishmentSuggestionResponse.builder()
                    .windowDays(window).since(since).kiosks(List.of()).build();
        }
        Set<Long> allIds = kiosks.stream().map(KioskRow::locationId).collect(Collectors.toSet());

        Map<String, LineAgg> lines = new LinkedHashMap<>();
        loadStock(allIds, lines);
        loadSales(allIds, since, lines);
        loadInTransit(allIds, today.minusDays(PENDING_SHIPMENT_MAX_AGE_DAYS), lines);

        Map<Long, ProductEntity> products = productRepository.findAllById(
                        lines.values().stream().map(l -> l.productId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        lines.values().removeIf(l -> !isReplenishable(products.get(l.productId)));

        Map<Long, BigDecimal> unitsByKiosk = new HashMap<>();
        Map<Long, Set<Long>> kiosksSellingProduct = new HashMap<>();
        for (Long id : allIds) {
            unitsByKiosk.put(id, BigDecimal.ZERO);
        }
        for (LineAgg l : lines.values()) {
            if (l.sold.signum() > 0) {
                unitsByKiosk.merge(l.locationId, l.sold, BigDecimal::add);
                kiosksSellingProduct.computeIfAbsent(l.productId, k -> new HashSet<>()).add(l.locationId);
            }
        }
        Map<Long, Category> ranked = KioskReplenishmentCalculator.rankCategories(unitsByKiosk);
        Set<Long> baseProducts = new HashSet<>();
        int kioskCount = allIds.size();
        kiosksSellingProduct.forEach((productId, sellers) -> {
            if (sellers.size() >= Math.ceil(kioskCount * KioskReplenishmentCalculator.BASE_PRODUCT_KIOSK_SHARE)) {
                baseProducts.add(productId);
            }
        });

        Set<Long> requested = locationIds == null || locationIds.isEmpty()
                ? allIds
                : new HashSet<>(locationIds);
        Map<Long, List<LineAgg>> linesByKiosk = lines.values().stream()
                .filter(l -> requested.contains(l.locationId))
                .filter(l -> l.sold.signum() > 0)
                .collect(Collectors.groupingBy(l -> l.locationId, LinkedHashMap::new, Collectors.toList()));

        List<PlannedLine> planned = new ArrayList<>();
        Map<Long, Category> categoryByKiosk = new HashMap<>();
        for (KioskRow k : kiosks) {
            if (!requested.contains(k.locationId())) {
                continue;
            }
            Category category = k.category() != null ? k.category() : ranked.getOrDefault(k.locationId(), Category.C);
            categoryByKiosk.put(k.locationId(), category);
            List<LineAgg> kioskLines = linesByKiosk.getOrDefault(k.locationId(), List.of());
            List<BigDecimal> rates = new ArrayList<>();
            for (LineAgg l : kioskLines) {
                rates.add(KioskReplenishmentCalculator.plan(category, window,
                        new LineInput(0, 0, l.sold, false, false)).dailyRate());
            }
            BigDecimal threshold = KioskReplenishmentCalculator.topSellerThreshold(rates);
            for (int i = 0; i < kioskLines.size(); i++) {
                LineAgg l = kioskLines.get(i);
                BigDecimal rate = rates.get(i);
                boolean top = threshold != null && rate.signum() > 0 && rate.compareTo(threshold) >= 0;
                boolean base = baseProducts.contains(l.productId);
                LinePlan plan = KioskReplenishmentCalculator.plan(category, window,
                        new LineInput(l.stock, l.inTransit, l.sold, top, base));
                planned.add(new PlannedLine(l, category, plan, top, base));
            }
        }

        Map<String, Integer> available = loadAvailability(planned);
        List<AllocationRequest> requests = planned.stream()
                .map(p -> new AllocationRequest(p.line().stockKey(), p.category(), p.base(),
                        p.plan().dailyRate(), p.plan().need()))
                .toList();
        int[] allocated = KioskReplenishmentCalculator.allocate(requests, available);

        Map<Long, ColorEntity> colors = colorRepository.findAllById(
                        planned.stream().map(p -> p.line().colorId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ColorEntity::getId, Function.identity()));

        Map<Long, List<KioskReplenishmentSuggestionResponse.Line>> responseLines = new HashMap<>();
        for (int i = 0; i < planned.size(); i++) {
            PlannedLine p = planned.get(i);
            if (p.plan().need() <= 0) {
                continue;
            }
            LineAgg l = p.line();
            ProductEntity product = products.get(l.productId);
            ColorEntity color = l.colorId == null ? null : colors.get(l.colorId);
            responseLines.computeIfAbsent(l.locationId, k -> new ArrayList<>()).add(
                    KioskReplenishmentSuggestionResponse.Line.builder()
                            .productId(l.productId)
                            .productCode(product != null ? product.getCode() : null)
                            .productName(product != null ? product.getName() : null)
                            .colorId(l.colorId)
                            .colorName(color != null ? color.getName() : null)
                            .size(l.size)
                            .hardwareCondition(l.hardware)
                            .stock(l.stock)
                            .inTransit(l.inTransit)
                            .sold(l.sold)
                            .dailyRate(p.plan().dailyRate())
                            .min(p.plan().min())
                            .max(p.plan().max())
                            .need(p.plan().need())
                            .suggested(allocated[i])
                            .shortage(p.plan().need() - allocated[i])
                            .topSeller(p.top())
                            .base(p.base())
                            .reason(p.plan().reason())
                            .build());
        }

        List<KioskReplenishmentSuggestionResponse.Kiosk> out = new ArrayList<>();
        int totalShortage = 0;
        for (KioskRow k : kiosks) {
            Category category = categoryByKiosk.get(k.locationId());
            if (category == null) {
                continue;
            }
            List<KioskReplenishmentSuggestionResponse.Line> kl = responseLines.getOrDefault(k.locationId(), new ArrayList<>());
            kl.sort(Comparator.comparingInt(KioskReplenishmentSuggestionResponse.Line::getSuggested).reversed()
                    .thenComparing(x -> Objects.toString(x.getProductCode(), ""))
                    .thenComparing(x -> Objects.toString(x.getSize(), "")));
            int suggested = kl.stream().mapToInt(KioskReplenishmentSuggestionResponse.Line::getSuggested).sum();
            int shortage = kl.stream().mapToInt(KioskReplenishmentSuggestionResponse.Line::getShortage).sum();
            totalShortage += shortage;
            out.add(KioskReplenishmentSuggestionResponse.Kiosk.builder()
                    .locationId(k.locationId())
                    .code(k.code())
                    .name(k.name())
                    .category(category.name())
                    .categorySource(k.category() != null ? "FINANZAS" : "CALCULADA")
                    .unitsSold(unitsByKiosk.getOrDefault(k.locationId(), BigDecimal.ZERO))
                    .totalSuggested(suggested)
                    .totalShortage(shortage)
                    .lines(kl)
                    .build());
        }
        out.sort(Comparator.comparing(KioskReplenishmentSuggestionResponse.Kiosk::getCategory)
                .thenComparing(x -> Objects.toString(x.getName(), "")));

        return KioskReplenishmentSuggestionResponse.builder()
                .windowDays(window)
                .since(since)
                .kiosks(out)
                .totalShortage(totalShortage)
                .build();
    }

    private record PlannedLine(LineAgg line, Category category, LinePlan plan, boolean top, boolean base) {
    }

    private List<KioskRow> loadKiosks() {
        String sql = """
                SELECT DISTINCT ON (s.location_id) s.location_id, l.code, l.name, s.sales_category
                FROM kiosk_site s
                JOIN locations l ON l.id = s.location_id
                WHERE s.status = 'ACTIVE'
                  AND s.location_id IS NOT NULL
                  AND COALESCE(s.exclude_from_reports, FALSE) = FALSE
                ORDER BY s.location_id, s.sales_category NULLS LAST, s.id
                """;
        return jdbc.query(sql, new MapSqlParameterSource(), (rs, i) -> new KioskRow(
                rs.getLong("location_id"),
                rs.getString("code"),
                rs.getString("name"),
                Category.parse(rs.getString("sales_category"))));
    }

    private void loadStock(Set<Long> locationIds, Map<String, LineAgg> lines) {
        String sql = """
                SELECT location_id, product_id, color_id, hardware_condition, current_stock, sizes_data
                FROM kiosco_stock
                WHERE location_id IN (:ids)
                """;
        jdbc.query(sql, new MapSqlParameterSource("ids", locationIds), rs -> {
            Long locationId = rs.getLong("location_id");
            Long productId = rs.getLong("product_id");
            Long colorId = nullableLong(rs.getObject("color_id"));
            String hardware = normalizeHardware(rs.getString("hardware_condition"));
            Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(rs.getString("sizes_data"));
            boolean hasPositiveSizes = sizes.values().stream().anyMatch(v -> v != null && v.signum() > 0);
            if (hasPositiveSizes) {
                for (Map.Entry<String, BigDecimal> e : sizes.entrySet()) {
                    int qty = e.getValue() == null ? 0 : e.getValue().intValue();
                    if (qty > 0) {
                        line(lines, locationId, productId, colorId, e.getKey(), hardware).stock += qty;
                    }
                }
            } else {
                int qty = rs.getInt("current_stock");
                if (qty > 0) {
                    line(lines, locationId, productId, colorId, "", hardware).stock += qty;
                }
            }
        });
    }

    /** Venta neta (VENTA − ANULACION) por línea desde {@code since}. */
    private void loadSales(Set<Long> locationIds, LocalDate since, Map<String, LineAgg> lines) {
        String sql = """
                SELECT ks.location_id, ks.product_id, ks.color_id, ks.hardware_condition,
                       COALESCE(m.size_key, '') AS size_key,
                       SUM(CASE WHEN m.movement_type = 'VENTA' THEN ABS(m.quantity) ELSE -ABS(m.quantity) END) AS sold
                FROM kiosco_movement m
                JOIN kiosco_stock ks ON ks.id = m.kiosco_stock_id
                WHERE m.movement_type IN ('VENTA', 'ANULACION')
                  AND m.created_at >= :since
                  AND ks.location_id IN (:ids)
                GROUP BY ks.location_id, ks.product_id, ks.color_id, ks.hardware_condition, COALESCE(m.size_key, '')
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("ids", locationIds)
                .addValue("since", Timestamp.valueOf(since.atStartOfDay()));
        jdbc.query(sql, params, rs -> {
            BigDecimal sold = rs.getBigDecimal("sold");
            if (sold == null || sold.signum() <= 0) {
                return;
            }
            LineAgg l = line(lines, rs.getLong("location_id"), rs.getLong("product_id"),
                    nullableLong(rs.getObject("color_id")), rs.getString("size_key"),
                    normalizeHardware(rs.getString("hardware_condition")));
            l.sold = l.sold.add(sold);
        });
    }

    /** Envíos al kiosco que siguen sin recibirse: cuentan como "en camino" y se descuentan de la necesidad. */
    private void loadInTransit(Set<Long> locationIds, LocalDate pendingSince, Map<String, LineAgg> lines) {
        String sql = """
                SELECT ps.location_id, d.product_id, d.color_id,
                       COALESCE(NULLIF(UPPER(TRIM(d.hardware_condition)), ''), 'NUEVO') AS hardware,
                       COALESCE(d.size_label, '') AS size_label,
                       SUM(d.quantity) AS pending
                FROM product_shipment ps
                JOIN product_shipment_detail d ON d.shipment_id = ps.id
                WHERE ps.location_id IN (:ids)
                  AND ps.received_at IS NULL
                  AND UPPER(COALESCE(ps.status, '')) NOT IN ('CANCELLED', 'DELIVERED', 'COMPLETED', 'RECEIVED')
                  AND ps.created_at >= :pendingSince
                GROUP BY ps.location_id, d.product_id, d.color_id,
                         COALESCE(NULLIF(UPPER(TRIM(d.hardware_condition)), ''), 'NUEVO'), COALESCE(d.size_label, '')
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("ids", locationIds)
                .addValue("pendingSince", Timestamp.valueOf(pendingSince.atStartOfDay()));
        jdbc.query(sql, params, rs -> {
            BigDecimal pending = rs.getBigDecimal("pending");
            LineAgg l = line(lines, rs.getLong("location_id"), rs.getLong("product_id"),
                    nullableLong(rs.getObject("color_id")), rs.getString("size_label"), rs.getString("hardware"));
            l.inTransit += pending == null ? 0 : Math.max(0, pending.intValue());
        });
    }

    /** Disponible en Bodega PT + Devoluciones solo para las líneas que piden algo. */
    private Map<String, Integer> loadAvailability(List<PlannedLine> planned) throws BusinessException {
        Map<String, Integer> available = new HashMap<>();
        for (PlannedLine p : planned) {
            if (p.plan().need() <= 0 || available.containsKey(p.line().stockKey())) {
                continue;
            }
            LineAgg l = p.line();
            BigDecimal qty = productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                    l.productId, l.colorId, l.size);
            available.put(l.stockKey(), qty == null ? 0 : Math.max(0, qty.intValue()));
        }
        return available;
    }

    private static LineAgg line(Map<String, LineAgg> lines, Long locationId, Long productId, Long colorId,
                                String size, String hardware) {
        String normalizedSize = ProductInventorySizesJson.normalizeKey(size).toUpperCase();
        String key = locationId + "|" + productId + "|" + (colorId == null ? "" : colorId) + "|"
                + normalizedSize + "|" + hardware;
        return lines.computeIfAbsent(key, k -> new LineAgg(locationId, productId, colorId, normalizedSize, hardware));
    }

    private static boolean isReplenishable(ProductEntity product) {
        if (product == null) {
            return false;
        }
        if (ProductCinchoType.isPackagingProductCode(product.getCode())) {
            return false;
        }
        String status = product.getStatus() == null ? "" : product.getStatus().trim().toUpperCase();
        return !status.equals("INACTIVE") && !status.equals("INACTIVO");
    }

    /**
     * NUEVO y VIEJO son la misma línea para reponer: el kiosco vende de ambos, pero bodega siempre envía NUEVO
     * (en prod ~18% de la venta de kiosco sale de stock VIEJO y ningún envío lleva VIEJO). Otras dimensiones
     * (marca, NIÑO/DAMA) se respetan.
     */
    private static String normalizeHardware(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase();
        return v.isEmpty() || v.equals("VIEJO") ? NO_HARDWARE : v;
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
