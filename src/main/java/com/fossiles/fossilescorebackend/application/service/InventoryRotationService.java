package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.InventoryRotationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryRotationResponse.Row;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryRotationResponse.Summary;
import com.fossiles.fossilescorebackend.application.util.InventoryRotationClassifier;
import com.fossiles.fossilescorebackend.application.util.InventoryRotationClassifier.Profile;
import com.fossiles.fossilescorebackend.application.util.InventoryRotationClassifier.Status;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reporte de rotación y cobertura de inventario (Reportes &gt; Inventarios). No mueve nada: cruza la existencia
 * actual con lo que se vendió (producto en kioscos) o consumió (materiales) en la ventana elegida.
 *
 * <p>Producto: la venta es {@code VENTA − ANULACION} de {@code kiosco_movement}; las ventas sin factura (SALIDA)
 * no cuentan como venta, igual que en la reposición. NUEVO y VIEJO se mantienen separados (el inventario y los
 * conteos así los manejan). Materiales: salidas EXIT del kardex de materiales, sin los ajustes.
 */
@Service
@RequiredArgsConstructor
public class InventoryRotationService {

    static final int DEFAULT_WINDOW_DAYS = 30;
    private static final String NO_HARDWARE = "NUEVO";

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;

    private static final class ProductAgg {
        final Long productId;
        final Long colorId;
        final String hardware;
        BigDecimal stock = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        Set<Long> locationsWithStock = new HashSet<>();
        LocalDate lastSale;

        ProductAgg(Long productId, Long colorId, String hardware) {
            this.productId = productId;
            this.colorId = colorId;
            this.hardware = hardware;
        }
    }

    @Transactional(readOnly = true)
    public InventoryRotationResponse products(Integer windowDays, List<Long> locationIds) {
        int window = normalizeWindow(windowDays);
        LocalDate since = GuatemalaDateTime.today().minusDays(window);
        Profile profile = InventoryRotationClassifier.KIOSK;

        Set<Long> kioskIds = loadKioskIds();
        if (locationIds != null && !locationIds.isEmpty()) {
            kioskIds.retainAll(new HashSet<>(locationIds));
        }
        Map<String, ProductAgg> aggs = new LinkedHashMap<>();
        if (!kioskIds.isEmpty()) {
            loadProductStock(kioskIds, aggs);
            loadProductSales(kioskIds, since, aggs);
        }

        Map<Long, ProductEntity> products = productRepository.findAllById(
                        aggs.values().stream().map(a -> a.productId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        Map<Long, String> colors = colorRepository.findAll().stream()
                .collect(Collectors.toMap(ColorEntity::getId, c -> c.getName() == null ? "" : c.getName(), (a, b) -> a));

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, ProductAgg> e : aggs.entrySet()) {
            ProductAgg a = e.getValue();
            ProductEntity product = products.get(a.productId);
            if (product == null || !isReportable(product)) {
                continue;
            }
            Status status = InventoryRotationClassifier.classify(a.stock, a.sold, window, profile);
            if (status == null) {
                continue;
            }
            BigDecimal price = product.getSalePrice() == null ? BigDecimal.ZERO : product.getSalePrice();
            BigDecimal rate = InventoryRotationClassifier.dailyRate(a.sold, window);
            rows.add(Row.builder()
                    .key(e.getKey())
                    .code(product.getCode())
                    .name(product.getName())
                    .variant(a.colorId == null ? "" : colors.getOrDefault(a.colorId, ""))
                    .hardware(a.hardware)
                    .stock(a.stock)
                    .moved(a.sold)
                    .dailyRate(rate)
                    .coverageDays(InventoryRotationClassifier.coverageDays(a.stock, rate))
                    .status(status.name())
                    .unitValue(price)
                    .stockValue(price.multiply(a.stock.max(BigDecimal.ZERO)))
                    .lastMovementDate(a.lastSale)
                    .locationsWithStock(a.locationsWithStock.size())
                    .build());
        }
        return build("PRODUCTS", window, since, profile, rows);
    }

    @Transactional(readOnly = true)
    public InventoryRotationResponse materials(Integer windowDays) {
        int window = normalizeWindow(windowDays);
        LocalDate since = GuatemalaDateTime.today().minusDays(window);
        Profile profile = InventoryRotationClassifier.MATERIAL;

        Map<Long, BigDecimal> used = new HashMap<>();
        Map<Long, LocalDate> lastUse = new HashMap<>();
        jdbc.query("""
                SELECT material_id, SUM(ABS(quantity)) AS used, MAX(movement_date) AS last_date
                FROM material_inventory_kardex
                WHERE movement_type = 'EXIT'
                  AND COALESCE(UPPER(reference_type), '') NOT LIKE 'ADJUST%'
                  AND movement_date >= :since
                GROUP BY material_id
                """, new MapSqlParameterSource("since", Timestamp.valueOf(since.atStartOfDay())), rs -> {
            long id = rs.getLong("material_id");
            used.put(id, rs.getBigDecimal("used"));
            Timestamp last = rs.getTimestamp("last_date");
            if (last != null) {
                lastUse.put(id, last.toLocalDateTime().toLocalDate());
            }
        });

        List<Row> rows = new ArrayList<>();
        jdbc.query("""
                SELECT m.id, m.sku, m.name, COALESCE(u.name, u.code) AS uom, m.unit_cost,
                       m."min" AS min_stock, m."max" AS max_stock, COALESCE(mi.quantity, 0) AS stock
                FROM material m
                LEFT JOIN material_inventory mi ON mi.material_id = m.id
                LEFT JOIN uom u ON u.id = m.manufacturing_uom_id
                WHERE COALESCE(UPPER(m.status), 'ACTIVE') NOT IN ('INACTIVE', 'INACTIVO')
                """, new MapSqlParameterSource(), rs -> {
            long id = rs.getLong("id");
            BigDecimal stock = rs.getBigDecimal("stock");
            BigDecimal moved = used.getOrDefault(id, BigDecimal.ZERO);
            Status status = InventoryRotationClassifier.classify(stock, moved, window, profile);
            if (status == null) {
                return;
            }
            BigDecimal cost = rs.getBigDecimal("unit_cost") == null ? BigDecimal.ZERO : rs.getBigDecimal("unit_cost");
            BigDecimal rate = InventoryRotationClassifier.dailyRate(moved, window);
            rows.add(Row.builder()
                    .key("M" + id)
                    .code(rs.getString("sku"))
                    .name(rs.getString("name"))
                    .unit(rs.getString("uom"))
                    .stock(stock)
                    .moved(moved)
                    .dailyRate(rate)
                    .coverageDays(InventoryRotationClassifier.coverageDays(stock, rate))
                    .status(status.name())
                    .unitValue(cost)
                    .stockValue(cost.multiply(stock.max(BigDecimal.ZERO)))
                    .lastMovementDate(lastUse.get(id))
                    .min(rs.getObject("min_stock") == null ? null : rs.getInt("min_stock"))
                    .max(rs.getObject("max_stock") == null ? null : rs.getInt("max_stock"))
                    .build());
        });
        return build("MATERIALS", window, since, profile, rows);
    }

    private InventoryRotationResponse build(String scope, int window, LocalDate since, Profile profile,
                                            List<Row> rows) {
        rows.sort(Comparator
                .comparingInt((Row r) -> Status.valueOf(r.getStatus()).severity())
                .thenComparing((Row r) -> r.getCoverageDays() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : r.getCoverageDays())
                .thenComparing(Row::getMoved, Comparator.reverseOrder())
                .thenComparing(r -> r.getName() == null ? "" : r.getName()));

        Summary s = Summary.builder()
                .totalStock(BigDecimal.ZERO).totalStockValue(BigDecimal.ZERO)
                .slowValue(BigDecimal.ZERO).noMovementValue(BigDecimal.ZERO).build();
        for (Row r : rows) {
            s.setLines(s.getLines() + 1);
            s.setTotalStock(s.getTotalStock().add(r.getStock().max(BigDecimal.ZERO)));
            s.setTotalStockValue(s.getTotalStockValue().add(r.getStockValue()));
            switch (Status.valueOf(r.getStatus())) {
                case SIN_STOCK -> s.setOutOfStockCount(s.getOutOfStockCount() + 1);
                case SE_AGOTA -> s.setSoonOutCount(s.getSoonOutCount() + 1);
                case NORMAL -> s.setNormalCount(s.getNormalCount() + 1);
                case LENTO -> {
                    s.setSlowCount(s.getSlowCount() + 1);
                    s.setSlowValue(s.getSlowValue().add(r.getStockValue()));
                }
                case SIN_MOVIMIENTO -> {
                    s.setNoMovementCount(s.getNoMovementCount() + 1);
                    s.setNoMovementValue(s.getNoMovementValue().add(r.getStockValue()));
                }
            }
        }
        return InventoryRotationResponse.builder()
                .scope(scope).windowDays(window).since(since)
                .shortDays(profile.shortDays()).slowDays(profile.slowDays())
                .summary(s).rows(rows).build();
    }

    private Set<Long> loadKioskIds() {
        List<Long> ids = jdbc.query("""
                SELECT DISTINCT s.location_id
                FROM kiosk_site s
                WHERE s.status = 'ACTIVE'
                  AND s.location_id IS NOT NULL
                  AND COALESCE(s.exclude_from_reports, FALSE) = FALSE
                """, new MapSqlParameterSource(), (rs, i) -> rs.getLong("location_id"));
        return new HashSet<>(ids);
    }

    private void loadProductStock(Set<Long> locationIds, Map<String, ProductAgg> aggs) {
        jdbc.query("""
                SELECT location_id, product_id, color_id, hardware_condition, current_stock, sizes_data
                FROM kiosco_stock
                WHERE location_id IN (:ids)
                """, new MapSqlParameterSource("ids", locationIds), rs -> {
            long locationId = rs.getLong("location_id");
            ProductAgg a = agg(aggs, rs.getLong("product_id"), nullableLong(rs.getObject("color_id")),
                    normalizeHardware(rs.getString("hardware_condition")));
            Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(rs.getString("sizes_data"));
            BigDecimal fromSizes = sizes.values().stream()
                    .filter(v -> v != null && v.signum() > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal qty = fromSizes.signum() > 0 ? fromSizes : BigDecimal.valueOf(rs.getInt("current_stock"));
            if (qty.signum() > 0) {
                a.stock = a.stock.add(qty);
                a.locationsWithStock.add(locationId);
            }
        });
    }

    /** Venta neta (VENTA − ANULACION) por línea desde {@code since}, con la fecha de la última venta. */
    private void loadProductSales(Set<Long> locationIds, LocalDate since, Map<String, ProductAgg> aggs) {
        jdbc.query("""
                SELECT ks.product_id, ks.color_id, ks.hardware_condition,
                       SUM(CASE WHEN m.movement_type = 'VENTA' THEN ABS(m.quantity) ELSE -ABS(m.quantity) END) AS sold,
                       MAX(CASE WHEN m.movement_type = 'VENTA' THEN m.created_at END) AS last_sale
                FROM kiosco_movement m
                JOIN kiosco_stock ks ON ks.id = m.kiosco_stock_id
                WHERE m.movement_type IN ('VENTA', 'ANULACION')
                  AND m.created_at >= :since
                  AND ks.location_id IN (:ids)
                GROUP BY ks.product_id, ks.color_id, ks.hardware_condition
                """, new MapSqlParameterSource("ids", locationIds)
                .addValue("since", Timestamp.valueOf(since.atStartOfDay())), rs -> {
            BigDecimal sold = rs.getBigDecimal("sold");
            if (sold == null || sold.signum() <= 0) {
                return;
            }
            ProductAgg a = agg(aggs, rs.getLong("product_id"), nullableLong(rs.getObject("color_id")),
                    normalizeHardware(rs.getString("hardware_condition")));
            a.sold = a.sold.add(sold);
            Timestamp last = rs.getTimestamp("last_sale");
            if (last != null) {
                LocalDate day = last.toLocalDateTime().toLocalDate();
                if (a.lastSale == null || day.isAfter(a.lastSale)) {
                    a.lastSale = day;
                }
            }
        });
    }

    private static ProductAgg agg(Map<String, ProductAgg> aggs, Long productId, Long colorId, String hardware) {
        String key = productId + "|" + (colorId == null ? "" : colorId) + "|" + hardware;
        return aggs.computeIfAbsent(key, k -> new ProductAgg(productId, colorId, hardware));
    }

    private static boolean isReportable(ProductEntity product) {
        if (ProductCinchoType.isPackagingProductCode(product.getCode())) {
            return false;
        }
        String status = product.getStatus() == null ? "" : product.getStatus().trim().toUpperCase();
        return !status.equals("INACTIVE") && !status.equals("INACTIVO");
    }

    private static int normalizeWindow(Integer windowDays) {
        return windowDays == null || windowDays < 7 ? DEFAULT_WINDOW_DAYS : Math.min(windowDays, 365);
    }

    private static String normalizeHardware(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase();
        return v.isEmpty() ? NO_HARDWARE : v;
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
