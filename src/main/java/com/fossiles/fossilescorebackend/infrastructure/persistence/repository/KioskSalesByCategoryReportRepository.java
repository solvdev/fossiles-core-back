package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Lecturas del reporte de ventas de kiosko por categoría.
 *
 * <p>Ventas: una lectura de líneas. El importe cobrado se calcula en Java
 * ({@code KioskSalePricing.paidLineAmounts}) porque el descuento de cabecera se prorratea
 * entre las líneas que no son empaque y el centavo de residuo cae en la línea mayor.
 * Fecha: {@code sale_date BETWEEN} (LocalDate en hora Guatemala; índice
 * {@code idx_kiosk_sale_date_kiosk}). No se usa {@code sold_at}.
 * Venta válida: {@code test_sale} no es true y el status, en mayúsculas y sin espacios,
 * no es VOID ni CANCELLED. Status nulo o en blanco cuenta. {@code fel_status} no filtra.
 *
 * <p>Devoluciones ({@code RETURN}, status COMPLETED, PENDING_REINTEGRO o REINTEGRATED):
 * restan unidades e importe al precio pagado. También entran las anteriores a {@code from}
 * solo para topar el importe (no se restan de nuevo en este rango).
 * Cambios ({@code EXCHANGE} COMPLETED dentro del rango): restan solo unidades.
 * Fecha: {@code COALESCE(completed_at, created_at) >= from 00:00 AND < to+1 00:00},
 * sin AT TIME ZONE (hora de pared de Guatemala).
 * Si hay filas en {@code kiosk_exchange_slip_returned_item} se usan esas; si no, la cabecera.
 *
 * <p>Entrecueros: se excluye a nivel de local. Las consultas unen {@code locations}
 * por {@code kiosk_location_id} y omiten {@code pos_mode = 'ENTRECUEROS'} o un nombre
 * que coincida con {@code ILIKE '%entrecuero%'} (ventas y devoluciones).
 */
@Repository
@RequiredArgsConstructor
public class KioskSalesByCategoryReportRepository {

    static final String SALES_SQL = """
            SELECT s.id AS sale_id,
                   s.discount_amount AS sale_discount,
                   i.id AS line_id,
                   i.product_id AS product_id,
                   i.product_code AS product_code,
                   i.quantity AS quantity,
                   i.unit_price AS unit_price,
                   i.line_total AS line_total,
                   i.discount_amount AS line_discount,
                   c.id AS category_id,
                   c.name AS category_name,
                   p.id AS catalog_product_id,
                   p.code AS catalog_product_code,
                   p.name AS catalog_product_name,
                   p.audience_category AS audience_category,
                   p.cincho_type AS cincho_type,
                   p.cincho_for_kids AS cincho_for_kids
            FROM kiosk_sale_item i
            JOIN kiosk_sale s ON s.id = i.kiosk_sale_id
            JOIN locations loc ON loc.id = s.kiosk_location_id
             AND COALESCE(loc.pos_mode, 'STANDARD') <> 'ENTRECUEROS'
             AND LOWER(COALESCE(loc.name, '')) NOT LIKE '%entrecuero%'
            LEFT JOIN product p ON p.id = i.product_id
            LEFT JOIN product_category c ON c.id = p.category_id
            WHERE s.sale_date BETWEEN :from AND :to
              AND COALESCE(s.test_sale, FALSE) = FALSE
              AND COALESCE(UPPER(TRIM(s.status)), '') NOT IN ('VOID', 'CANCELLED')
              AND s.kiosk_location_id IN (:kioskIds)
            """;

    static final String RETURNS_SQL = """
            WITH slips AS (
                SELECT r.id,
                       r.slip_type,
                       r.returned_product_id,
                       r.returned_quantity,
                       r.returned_amount,
                       r.original_sale_id,
                       r.original_sale_item_id,
                       COALESCE(r.completed_at, r.created_at) AS returned_at,
                       CASE
                           WHEN COALESCE(r.completed_at, r.created_at) >= :fromStart
                            AND COALESCE(r.completed_at, r.created_at) < :toExclusive
                           THEN 1 ELSE 0
                       END AS in_period
                FROM kiosk_exchange_slip r
                JOIN locations loc ON loc.id = r.kiosk_location_id
                 AND COALESCE(loc.pos_mode, 'STANDARD') <> 'ENTRECUEROS'
                 AND LOWER(COALESCE(loc.name, '')) NOT LIKE '%entrecuero%'
                WHERE r.kiosk_location_id IN (:kioskIds)
                  AND COALESCE(r.completed_at, r.created_at) < :toExclusive
                  AND (
                        (UPPER(TRIM(r.slip_type)) = 'RETURN'
                            AND UPPER(TRIM(r.status)) IN ('PENDING_REINTEGRO', 'COMPLETED', 'REINTEGRATED'))
                     OR (UPPER(TRIM(r.slip_type)) = 'EXCHANGE'
                            AND UPPER(TRIM(r.status)) = 'COMPLETED'
                            AND COALESCE(r.completed_at, r.created_at) >= :fromStart)
                  )
            )
            SELECT r.slip_type AS slip_kind,
                   r.in_period AS in_period,
                   r.returned_at AS returned_at,
                   r.id AS slip_id,
                   r.returned_product_id AS header_product_id,
                   r.returned_quantity AS header_quantity,
                   r.returned_amount AS header_amount,
                   r.original_sale_item_id AS header_sale_item_id,
                   ri.id AS returned_item_id,
                   ri.line_no AS item_line_no,
                   ri.product_id AS returned_item_product_id,
                   ri.quantity AS returned_item_quantity,
                   ri.line_total AS returned_item_line_total,
                   ri.original_sale_item_id AS returned_item_sale_item_id,
                   s.id AS sale_id,
                   s.discount_amount AS sale_discount,
                   i.id AS line_id,
                   i.product_code AS line_product_code,
                   i.quantity AS line_quantity,
                   i.unit_price AS line_unit_price,
                   i.line_total AS line_total,
                   i.discount_amount AS line_discount,
                   c.id AS category_id,
                   c.name AS category_name,
                   p.id AS catalog_product_id,
                   p.code AS catalog_product_code,
                   p.name AS catalog_product_name,
                   p.audience_category AS audience_category,
                   p.cincho_type AS cincho_type,
                   p.cincho_for_kids AS cincho_for_kids
            FROM slips r
            LEFT JOIN kiosk_exchange_slip_returned_item ri ON ri.exchange_slip_id = r.id
            LEFT JOIN kiosk_sale_item anchor
                   ON anchor.id = COALESCE(ri.original_sale_item_id, r.original_sale_item_id)
            LEFT JOIN kiosk_sale s ON s.id = COALESCE(anchor.kiosk_sale_id, r.original_sale_id)
            LEFT JOIN kiosk_sale_item i ON i.kiosk_sale_id = s.id
            LEFT JOIN product p ON p.id = COALESCE(ri.product_id, r.returned_product_id)
            LEFT JOIN product_category c ON c.id = p.category_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public List<SaleLine> findSaleLines(LocalDate from, LocalDate to, Collection<Long> kioskIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("kioskIds", kioskIds);
        return jdbc.query(SALES_SQL, params, (rs, rowNum) -> new SaleLine(
                asLong(rs, "sale_id"),
                rs.getBigDecimal("sale_discount"),
                asLong(rs, "line_id"),
                asLong(rs, "product_id"),
                rs.getString("product_code"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("line_total"),
                rs.getBigDecimal("line_discount"),
                asLong(rs, "category_id"),
                rs.getString("category_name"),
                asLong(rs, "catalog_product_id"),
                rs.getString("catalog_product_code"),
                rs.getString("catalog_product_name"),
                rs.getString("audience_category"),
                rs.getString("cincho_type"),
                asBoolean(rs, "cincho_for_kids")));
    }

    public List<ReturnRow> findReturns(
            LocalDateTime fromStart,
            LocalDateTime toExclusive,
            Collection<Long> kioskIds
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromStart", fromStart)
                .addValue("toExclusive", toExclusive)
                .addValue("kioskIds", kioskIds);
        return jdbc.query(RETURNS_SQL, params, (rs, rowNum) -> new ReturnRow(
                rs.getString("slip_kind"),
                rs.getInt("in_period") == 1,
                asDateTime(rs, "returned_at"),
                asLong(rs, "slip_id"),
                asLong(rs, "header_product_id"),
                rs.getBigDecimal("header_quantity"),
                rs.getBigDecimal("header_amount"),
                asLong(rs, "header_sale_item_id"),
                asLong(rs, "returned_item_id"),
                asInt(rs, "item_line_no"),
                asLong(rs, "returned_item_product_id"),
                rs.getBigDecimal("returned_item_quantity"),
                rs.getBigDecimal("returned_item_line_total"),
                asLong(rs, "returned_item_sale_item_id"),
                asLong(rs, "sale_id"),
                rs.getBigDecimal("sale_discount"),
                asLong(rs, "line_id"),
                rs.getString("line_product_code"),
                rs.getBigDecimal("line_quantity"),
                rs.getBigDecimal("line_unit_price"),
                rs.getBigDecimal("line_total"),
                rs.getBigDecimal("line_discount"),
                asLong(rs, "category_id"),
                rs.getString("category_name"),
                asLong(rs, "catalog_product_id"),
                rs.getString("catalog_product_code"),
                rs.getString("catalog_product_name"),
                rs.getString("audience_category"),
                rs.getString("cincho_type"),
                asBoolean(rs, "cincho_for_kids")));
    }

    private static Long asLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Boolean asBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static Integer asInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static LocalDateTime asDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record SaleLine(
            Long saleId,
            BigDecimal saleDiscount,
            Long lineId,
            Long productId,
            String productCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal lineDiscount,
            Long categoryId,
            String categoryName,
            Long catalogProductId,
            String catalogProductCode,
            String catalogProductName,
            String audienceCategory,
            String cinchoType,
            Boolean cinchoForKids
    ) {
    }

    public record ReturnRow(
            String slipKind,
            boolean inPeriod,
            LocalDateTime returnedAt,
            Long slipId,
            Long headerProductId,
            BigDecimal headerQuantity,
            BigDecimal headerAmount,
            Long headerSaleItemId,
            Long returnedItemId,
            Integer itemLineNo,
            Long returnedItemProductId,
            BigDecimal returnedItemQuantity,
            BigDecimal returnedItemLineTotal,
            Long returnedItemSaleItemId,
            Long saleId,
            BigDecimal saleDiscount,
            Long lineId,
            String lineProductCode,
            BigDecimal lineQuantity,
            BigDecimal lineUnitPrice,
            BigDecimal lineTotal,
            BigDecimal lineDiscount,
            Long categoryId,
            String categoryName,
            Long catalogProductId,
            String catalogProductCode,
            String catalogProductName,
            String audienceCategory,
            String cinchoType,
            Boolean cinchoForKids
    ) {
    }
}
