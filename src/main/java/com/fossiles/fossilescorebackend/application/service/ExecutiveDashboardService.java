package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.AdMonth;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.DebtorRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.DeskRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.Finance;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.GoalRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.InventoryBand;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.KioskRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.KioskSales;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.LeatherRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.MarginRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.OrderRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.Orders;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.PnlRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.Point;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.ProducedVsSold;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.Production;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.ProductQty;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.ProductTimeRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.Profitability;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.Receivables;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.SlowRow;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.TopProduct;
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.TypeCount;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsPnlResponse;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tablero gerencial: junta en una lectura lo que la gerencia necesita para decidir (producción y tiempos, avance de
 * órdenes, ventas de kioscos, rentabilidad por producto, inventario parado, cuero, cartera, publicidad y el P&amp;L
 * oficial de Finanzas por kiosco). Solo lectura; la respuesta se guarda 60 s en {@link SalesDashboardCache}.
 *
 * <p>Reglas comunes: ventas de kiosco = {@code kiosk_sale} COMPLETED sin ventas de prueba; kioscos = ubicaciones de
 * categoría KIOSKO salvo el de demostración (TST); piezas de producción sin las órdenes de cinchos por talla (sus
 * cantidades viven en {@code sizes_data}); productos de empaque (SUM*) fuera de rankings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutiveDashboardService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    private static final int WEEKS = 12;
    private static final int TOP_PRODUCTS = 15;
    private static final int TOP_PER_KIOSK = 10;
    private static final int PRODUCED_VS_SOLD_ROWS = 25;

    private static final String KIOSKS = "SELECT id FROM locations WHERE categoria = 'KIOSKO' AND COALESCE(code, '') <> 'TST'";
    private static final String WAREHOUSE_PT = "(SELECT id FROM locations WHERE code = 'BODEGA_PT' LIMIT 1)";
    private static final String KIOSK_SALE_OK = "NOT s.test_sale AND s.status = 'COMPLETED'";

    private final NamedParameterJdbcTemplate jdbc;
    private final SalesDashboardCache cache;
    private final KioskFinancialsReportService financialsReportService;
    private final ExecutiveDashboardAccessGuard guard;

    public ExecutiveDashboardResponse get(Integer days, boolean refresh) {
        guard.assertCanView();
        int window = days == null || days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate today = GuatemalaDateTime.today();
        // El P&L oficial revisa sus propios permisos: se lee fuera del caché compartido por quien tenga acceso.
        ExecutiveDashboardResponse base = cache.get(
                SalesDashboardCache.key("executive", today.minusDays(window - 1L), today, null), refresh,
                () -> build(window, today));
        return withPnl(base, today);
    }

    private ExecutiveDashboardResponse build(int window, LocalDate today) {
        LocalDate from = today.minusDays(window - 1L);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", Date.valueOf(from))
                .addValue("to", Date.valueOf(today))
                .addValue("fromTs", Timestamp.valueOf(from.atStartOfDay()))
                .addValue("toTs", Timestamp.valueOf(today.plusDays(1).atStartOfDay()))
                .addValue("prevFrom", Date.valueOf(from.minusDays(window)))
                .addValue("prevTo", Date.valueOf(from.minusDays(1)))
                .addValue("weeksFrom", Date.valueOf(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(WEEKS - 1L)))
                .addValue("since90", Date.valueOf(today.minusDays(89)))
                .addValue("since90Ts", Timestamp.valueOf(today.minusDays(89).atStartOfDay()))
                .addValue("today", Date.valueOf(today));

        Profitability profitability = profitability(p);
        return new ExecutiveDashboardResponse(
                from, today, window, GuatemalaDateTime.now(),
                production(p, today),
                orders(p, today),
                kioskSales(p, today),
                finance(today, null, null, null, null),
                profitability,
                leather(),
                receivables(p),
                ads(today));
    }

    // ------------------------------------------------------------------ Producción

    private Production production(MapSqlParameterSource p, LocalDate today) {
        long[] totals = new long[4];
        double[] minutes = new double[1];
        jdbc.query("""
                SELECT COUNT(*) AS tasks, COALESCE(SUM(quantity), 0) AS pieces,
                       COALESCE(SUM(actual_duration_minutes), 0) AS minutes,
                       COUNT(*) FILTER (WHERE delayed) AS delayed_tasks
                FROM task WHERE status = 'COMPLETED' AND completed_at >= :fromTs AND completed_at < :toTs
                """, p, rs -> {
            totals[0] = rs.getLong("tasks");
            totals[1] = rs.getLong("pieces");
            minutes[0] = rs.getDouble("minutes");
            totals[2] = rs.getLong("delayed_tasks");
        });
        LocalDate[] startedOn = new LocalDate[1];
        long[] all = new long[2];
        jdbc.query("""
                SELECT MIN(created_at) AS first_task,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED') AS tasks,
                       COALESCE(SUM(quantity) FILTER (WHERE status = 'COMPLETED'), 0) AS pieces
                FROM task
                """, p, rs -> {
            Timestamp first = rs.getTimestamp("first_task");
            startedOn[0] = first == null ? null : first.toLocalDateTime().toLocalDate();
            all[0] = rs.getLong("tasks");
            all[1] = rs.getLong("pieces");
        });

        Map<LocalDate, Double> weekly = new HashMap<>();
        jdbc.query("""
                SELECT date_trunc('week', completed_at)::date AS wk, SUM(quantity) AS pieces
                FROM task WHERE status = 'COMPLETED' AND completed_at >= :weeksFrom GROUP BY 1
                """, p, rs -> {
            weekly.put(rs.getDate("wk").toLocalDate(), rs.getDouble("pieces"));
        });
        List<Point> weeks = weekSeries(weekly, today);
        double avgWeek = weeks.stream().filter(w -> !w.current()).skip(Math.max(0, weeks.size() - 5L))
                .mapToDouble(Point::value).average().orElse(0);

        List<DeskRow> desks = jdbc.query("""
                SELECT COALESCE(worked_desk, desk) AS desk, COUNT(*) AS tasks, COALESCE(SUM(quantity), 0) AS pieces,
                       SUM(actual_duration_minutes) AS minutes
                FROM task WHERE status = 'COMPLETED' AND completed_at >= :fromTs AND completed_at < :toTs
                GROUP BY 1 ORDER BY 3 DESC
                """, p, (rs, i) -> {
            Double m = doubleOrNull(rs, "minutes");
            return new DeskRow(intOrNull(rs, "desk"), rs.getLong("tasks"), rs.getLong("pieces"),
                    m == null || m <= 0 ? null : round1(m / 60.0));
        });

        List<ProductTimeRow> productTime = jdbc.query("""
                SELECT t.product_code AS code, MAX(COALESCE(pr.name, t.product_name)) AS name,
                       SUM(t.quantity) AS pieces, SUM(t.actual_duration_minutes) AS minutes,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY t.actual_duration_minutes::numeric / t.quantity) AS median_min
                FROM task t LEFT JOIN product pr ON pr.id = t.product_id
                WHERE t.status = 'COMPLETED' AND t.completed_at >= :fromTs AND t.completed_at < :toTs
                  AND t.actual_duration_minutes > 0 AND t.quantity > 0 AND t.product_code IS NOT NULL
                GROUP BY t.product_code ORDER BY SUM(t.actual_duration_minutes) DESC LIMIT 15
                """, p, (rs, i) -> new ProductTimeRow(rs.getString("code"), rs.getString("name"),
                rs.getLong("pieces"), round1(rs.getDouble("minutes") / 60.0),
                round2(rs.getDouble("median_min") / 60.0)));

        long[] backlog = new long[4];
        jdbc.query(OPEN_ORDERS_CTE + """
                SELECT COALESCE(SUM(qty), 0) AS ordered, COALESCE(SUM(LEAST(done, qty)), 0) AS done,
                       COALESCE(SUM(LEAST(wip, GREATEST(qty - done, 0))), 0) AS wip,
                       COALESCE(SUM(GREATEST(qty - done - wip, 0)), 0) AS pending
                FROM open_orders WHERE order_type <> 'CINCHOS_FOSSILES'
                """, p, rs -> {
            backlog[0] = rs.getLong("ordered");
            backlog[1] = rs.getLong("done");
            backlog[2] = rs.getLong("wip");
            backlog[3] = rs.getLong("pending");
        });

        return new Production(totals[0], totals[1], round1(minutes[0] / 60.0), totals[2], startedOn[0], all[0], all[1],
                weeks, round1(avgWeek), desks, productTime, backlog[0], backlog[1], backlog[2], backlog[3]);
    }

    /** Órdenes PENDING/IN_PROGRESS con piezas pedidas (sin talla), terminadas y en proceso según sus tareas. */
    private static final String OPEN_ORDERS_CTE = """
            WITH oo AS (
                SELECT id, code, order_type, status, customer_name, delivery_date
                FROM production_order WHERE status IN ('PENDING', 'IN_PROGRESS')
            ), qty AS (
                SELECT production_order_id AS id, COALESCE(SUM(quantity), 0) AS qty
                FROM production_order_item WHERE production_order_id IN (SELECT id FROM oo) GROUP BY 1
            ), prog AS (
                SELECT production_order_id AS id,
                       COALESCE(SUM(quantity) FILTER (WHERE status = 'COMPLETED'), 0) AS done,
                       COALESCE(SUM(quantity) FILTER (WHERE status = 'IN_PROGRESS'), 0) AS wip
                FROM task WHERE production_order_id IN (SELECT id FROM oo) GROUP BY 1
            ), open_orders AS (
                SELECT oo.*, COALESCE(qty.qty, 0) AS qty, COALESCE(prog.done, 0) AS done, COALESCE(prog.wip, 0) AS wip
                FROM oo LEFT JOIN qty ON qty.id = oo.id LEFT JOIN prog ON prog.id = oo.id
            )
            """;

    // ------------------------------------------------------------------ Órdenes

    private Orders orders(MapSqlParameterSource p, LocalDate today) {
        long[] counts = new long[4];
        jdbc.query("""
                SELECT COUNT(*) AS open_orders,
                       COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                       COUNT(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
                       COUNT(*) FILTER (WHERE delivery_date < :today) AS overdue
                FROM production_order WHERE status IN ('PENDING', 'IN_PROGRESS')
                """, p, rs -> {
            counts[0] = rs.getLong("open_orders");
            counts[1] = rs.getLong("pending");
            counts[2] = rs.getLong("in_progress");
            counts[3] = rs.getLong("overdue");
        });
        List<TypeCount> byType = jdbc.query("""
                SELECT order_type, COUNT(*) AS n FROM production_order
                WHERE status IN ('PENDING', 'IN_PROGRESS') GROUP BY 1 ORDER BY 2 DESC
                """, p, (rs, i) -> new TypeCount(rs.getString("order_type"), rs.getLong("n")));
        List<OrderRow> rows = jdbc.query(OPEN_ORDERS_CTE + """
                SELECT id, code, order_type, customer_name, delivery_date, qty, done, wip FROM open_orders
                WHERE order_type <> 'CINCHOS_FOSSILES' AND qty > 0
                ORDER BY GREATEST(qty - done, 0) DESC, delivery_date NULLS LAST LIMIT 40
                """, p, (rs, i) -> new OrderRow(rs.getLong("id"), rs.getString("code"), rs.getString("order_type"),
                trim(rs.getString("customer_name")), localDate(rs, "delivery_date"),
                rs.getLong("qty"), rs.getLong("done"), rs.getLong("wip")));
        return new Orders(counts[0], counts[1], counts[2], counts[3], byType, rows);
    }

    // ------------------------------------------------------------------ Ventas de kioscos

    private KioskSales kioskSales(MapSqlParameterSource p, LocalDate today) {
        Map<Long, List<ProductQty>> topByKiosk = new HashMap<>();
        jdbc.query("""
                WITH x AS (
                    SELECT s.kiosk_location_id AS loc, i.product_code AS code, MAX(COALESCE(pr.name, i.product_name)) AS name,
                           SUM(i.quantity) AS q,
                           ROW_NUMBER() OVER (PARTITION BY s.kiosk_location_id ORDER BY SUM(i.quantity) DESC) AS rn
                    FROM kiosk_sale_item i JOIN kiosk_sale s ON s.id = i.kiosk_sale_id
                    LEFT JOIN product pr ON pr.id = i.product_id
                    WHERE %s AND s.sale_date BETWEEN :from AND :to AND COALESCE(i.product_code, '') NOT LIKE 'SUM%%'
                    GROUP BY 1, 2
                )
                SELECT loc, code, name, q FROM x WHERE rn <= %d ORDER BY loc, rn
                """.formatted(KIOSK_SALE_OK, TOP_PER_KIOSK), p, rs -> {
            topByKiosk.computeIfAbsent(rs.getLong("loc"), k -> new ArrayList<>())
                    .add(new ProductQty(rs.getString("code"), rs.getString("name"), rs.getDouble("q")));
        });

        List<KioskRow> kiosks = jdbc.query("""
                SELECT l.id, l.name, COUNT(s.id) AS tickets, COALESCE(SUM(s.total_amount), 0) AS sales,
                       COALESCE(SUM(s.total_items), 0) AS units
                FROM locations l
                LEFT JOIN kiosk_sale s ON s.kiosk_location_id = l.id AND %s AND s.sale_date BETWEEN :from AND :to
                WHERE l.id IN (%s)
                GROUP BY l.id, l.name ORDER BY 4 DESC
                """.formatted(KIOSK_SALE_OK, KIOSKS), p, (rs, i) -> {
            long id = rs.getLong("id");
            return new KioskRow(id, rs.getString("name"), rs.getLong("tickets"), round2(rs.getDouble("sales")),
                    rs.getDouble("units"), topByKiosk.getOrDefault(id, List.of()));
        });

        double sales = kiosks.stream().mapToDouble(KioskRow::sales).sum();
        long tickets = kiosks.stream().mapToLong(KioskRow::tickets).sum();
        double units = kiosks.stream().mapToDouble(KioskRow::units).sum();
        Double previous = jdbc.queryForObject("""
                SELECT COALESCE(SUM(s.total_amount), 0) FROM kiosk_sale s
                WHERE %s AND s.sale_date BETWEEN :prevFrom AND :prevTo AND s.kiosk_location_id IN (%s)
                """.formatted(KIOSK_SALE_OK, KIOSKS), p, Double.class);

        Map<LocalDate, Double> weekly = new HashMap<>();
        jdbc.query("""
                SELECT date_trunc('week', s.sale_date)::date AS wk, SUM(s.total_amount) AS amount FROM kiosk_sale s
                WHERE %s AND s.sale_date >= :weeksFrom AND s.kiosk_location_id IN (%s) GROUP BY 1
                """.formatted(KIOSK_SALE_OK, KIOSKS), p, rs -> {
            weekly.put(rs.getDate("wk").toLocalDate(), rs.getDouble("amount"));
        });

        List<TopProduct> top = jdbc.query("""
                WITH sold AS (
                    SELECT i.product_id, SUM(i.quantity) AS q, SUM(i.line_total) AS amount
                    FROM kiosk_sale_item i JOIN kiosk_sale s ON s.id = i.kiosk_sale_id
                    WHERE %s AND s.sale_date BETWEEN :from AND :to
                      AND COALESCE(i.product_code, '') NOT LIKE 'SUM%%' AND COALESCE(i.product_code, '') NOT LIKE 'FOSS%%'
                    GROUP BY 1 ORDER BY 2 DESC LIMIT %d
                ), k AS (%s),
                st AS (
                    SELECT ks.location_id, ks.product_id, SUM(ks.current_stock) AS s FROM kiosco_stock ks
                    WHERE ks.product_id IN (SELECT product_id FROM sold) AND ks.location_id IN (SELECT id FROM k)
                    GROUP BY 1, 2
                ), per_kiosk AS (
                    SELECT sold.product_id, k.id AS loc, COALESCE(st.s, 0) AS s
                    FROM sold CROSS JOIN k LEFT JOIN st ON st.product_id = sold.product_id AND st.location_id = k.id
                ), pt AS (
                    SELECT product_id, SUM(quantity) AS q FROM product_inventory_location
                    WHERE location_id = %s AND product_id IN (SELECT product_id FROM sold) GROUP BY 1
                )
                SELECT pr.code, pr.name, sold.q, sold.amount, COALESCE(pt.q, 0) AS pt,
                       (SELECT COALESCE(SUM(s), 0) FROM per_kiosk pk WHERE pk.product_id = sold.product_id) AS kiosk_stock,
                       (SELECT COUNT(*) FROM per_kiosk pk WHERE pk.product_id = sold.product_id AND pk.s <= 0) AS without_stock,
                       (SELECT COUNT(*) FROM per_kiosk pk WHERE pk.product_id = sold.product_id AND pk.s BETWEEN 1 AND 2) AS low_stock
                FROM sold JOIN product pr ON pr.id = sold.product_id LEFT JOIN pt ON pt.product_id = sold.product_id
                ORDER BY sold.q DESC
                """.formatted(KIOSK_SALE_OK, TOP_PRODUCTS, KIOSKS, WAREHOUSE_PT), p,
                (rs, i) -> new TopProduct(rs.getString("code"), rs.getString("name"), rs.getDouble("q"),
                        round2(rs.getDouble("amount")), rs.getLong("kiosk_stock"), rs.getLong("pt"),
                        rs.getLong("without_stock"), rs.getLong("low_stock")));

        return new KioskSales(round2(sales), round2(previous == null ? 0 : previous), tickets, units,
                weekSeries(weekly, today), kiosks, top);
    }

    // ------------------------------------------------------------------ Finanzas

    private Finance finance(LocalDate today, Integer pnlYear, Integer pnlMonth, List<PnlRow> pnl, String pnlUnavailable) {
        YearMonth ym = YearMonth.from(today);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("y", ym.getYear()).addValue("m", ym.getMonthValue())
                .addValue("monthStart", Date.valueOf(ym.atDay(1)))
                .addValue("today", Date.valueOf(today));
        List<GoalRow> goals = jdbc.query("""
                WITH k AS (%s),
                g AS (SELECT kiosk_location_id AS id, SUM(goal_amount) AS goal FROM kiosk_monthly_goal
                      WHERE goal_year = :y AND goal_month = :m GROUP BY 1),
                v AS (SELECT s.kiosk_location_id AS id, SUM(s.total_amount) AS mtd FROM kiosk_sale s
                      WHERE %s AND s.sale_date BETWEEN :monthStart AND :today GROUP BY 1)
                SELECT l.id, l.name, COALESCE(g.goal, 0) AS goal, COALESCE(v.mtd, 0) AS mtd
                FROM locations l LEFT JOIN g ON g.id = l.id LEFT JOIN v ON v.id = l.id
                WHERE l.id IN (SELECT id FROM k) ORDER BY 4 DESC
                """.formatted(KIOSKS, KIOSK_SALE_OK), p,
                (rs, i) -> new GoalRow(rs.getLong("id"), rs.getString("name"), round2(rs.getDouble("goal")),
                        round2(rs.getDouble("mtd"))));
        double mtd = goals.stream().mapToDouble(GoalRow::monthToDate).sum();

        // Mismos días del año pasado (hasta ayer): histórico importado + POS, por si el kiosco ya tenía sistema.
        LocalDate until = today.getDayOfMonth() > 1 ? today.minusDays(1) : today;
        LocalDate lyStart = ym.atDay(1).minusYears(1);
        LocalDate lyUntil = until.minusYears(1);
        double current = sumKioskSales(ym.atDay(1), until);
        double lastYearSame = sumHistory(lyStart, lyUntil);
        double lastYearMonth = sumHistory(lyStart, ym.minusYears(1).atEndOfMonth());
        YearMonth next = ym.plusMonths(1).minusYears(1);
        double lastYearNext = sumHistory(next.atDay(1), next.atEndOfMonth());

        double elapsed = today.getDayOfMonth() - 0.5;
        return new Finance(ym.getYear(), ym.getMonthValue(), elapsed, ym.lengthOfMonth(), goals, pnlYear, pnlMonth,
                pnl == null ? List.of() : pnl, pnlUnavailable, round2(current), round2(lastYearSame), until.getDayOfMonth(),
                round2(lastYearMonth), round2(lastYearNext));
    }

    private double sumKioskSales(LocalDate from, LocalDate to) {
        Double v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(s.total_amount), 0) FROM kiosk_sale s
                WHERE %s AND s.sale_date BETWEEN :from AND :to AND s.kiosk_location_id IN (%s)
                """.formatted(KIOSK_SALE_OK, KIOSKS),
                new MapSqlParameterSource("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)), Double.class);
        return v == null ? 0 : v;
    }

    private double sumHistory(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
        Double hist = jdbc.queryForObject("""
                SELECT COALESCE(SUM(h.amount), 0) FROM kiosk_daily_sales_hist h
                JOIN kiosk_site ks ON ks.id = h.site_id
                WHERE h.sale_date BETWEEN :from AND :to AND NOT COALESCE(ks.exclude_from_reports, false)
                """, p, Double.class);
        return (hist == null ? 0 : hist) + sumKioskSales(from, to);
    }

    /** P&amp;L oficial del último mes cerrado. Se calcula por usuario porque Finanzas valida sus propios permisos. */
    private ExecutiveDashboardResponse withPnl(ExecutiveDashboardResponse base, LocalDate today) {
        YearMonth last = YearMonth.from(today).minusMonths(1);
        List<PnlRow> rows = new ArrayList<>();
        String unavailable = null;
        try {
            Map<Long, Long> siteToLocation = new HashMap<>();
            jdbc.query("SELECT id, location_id FROM kiosk_site", new MapSqlParameterSource(), rs -> {
                long loc = rs.getLong("location_id");
                siteToLocation.put(rs.getLong("id"), rs.wasNull() ? null : loc);
            });
            KioskFinancialsPnlResponse pnl;
            try {
                pnl = financialsReportService.getPnl(last.getYear(), last.getMonthValue(), null);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (pnl != null && pnl.getSites() != null) {
                for (KioskFinancialsPnlResponse.SitePnl s : pnl.getSites()) {
                    double sales = num(s.getSales());
                    if (sales <= 0) {
                        continue;
                    }
                    double fixed = s.getFixed() == null ? 0 : num(s.getFixed().getTotal());
                    rows.add(new PnlRow(s.getSiteId(), siteToLocation.get(s.getSiteId()), s.getName(), round2(sales),
                            round2(fixed), round2(num(s.getDifference())), round2(num(s.getBreakEven()))));
                }
            }
        } catch (RuntimeException e) {
            log.info("Tablero gerencial sin P&L de Finanzas: {}", e.getMessage());
            unavailable = "No se pudo leer el P&L de Finanzas por kiosco (" + e.getMessage() + ").";
            rows = List.of();
        }
        Finance f = base.finance();
        Finance finance = new Finance(f.year(), f.month(), f.daysElapsed(), f.daysInMonth(), f.goals(),
                last.getYear(), last.getMonthValue(), rows, unavailable, f.monthToDate(), f.lastYearSameDays(),
                f.sameDaysUntil(), f.lastYearMonth(), f.lastYearNextMonth());
        return new ExecutiveDashboardResponse(base.from(), base.to(), base.days(), base.generatedAt(), base.production(),
                base.orders(), base.kioskSales(), finance, base.profitability(), base.leather(), base.receivables(),
                base.ads());
    }

    // ------------------------------------------------------------------ Rentabilidad

    private Profitability profitability(MapSqlParameterSource p) {
        List<ProducedVsSold> pvs = jdbc.query("""
                WITH prod AS (
                    SELECT t.product_code AS code,
                           SUM(t.quantity) FILTER (WHERE po.code LIKE 'OPK%%') AS for_kiosks,
                           SUM(t.quantity) FILTER (WHERE po.code NOT LIKE 'OPK%%' OR po.code IS NULL) AS for_clients
                    FROM task t LEFT JOIN production_order po ON po.id = t.production_order_id
                    WHERE t.status = 'COMPLETED' AND t.completed_at >= :fromTs AND t.completed_at < :toTs
                    GROUP BY 1
                ), ks AS (
                    SELECT i.product_code AS code, SUM(i.quantity) AS q
                    FROM kiosk_sale_item i JOIN kiosk_sale s ON s.id = i.kiosk_sale_id
                    WHERE %s AND s.sale_date BETWEEN :from AND :to GROUP BY 1
                ), os AS (
                    SELECT product_code AS code, SUM(quantity) AS q FROM online_sale
                    WHERE sale_date BETWEEN :from AND :to AND COALESCE(status, '') NOT IN ('ANULADA', 'DEVOLUCION')
                    GROUP BY 1
                ), pt AS (
                    SELECT pr.code, SUM(pil.quantity) AS q FROM product_inventory_location pil
                    JOIN product pr ON pr.id = pil.product_id WHERE pil.location_id = %s GROUP BY 1
                ), kst AS (
                    SELECT pr.code, SUM(ks.current_stock) AS q FROM kiosco_stock ks JOIN product pr ON pr.id = ks.product_id
                    WHERE ks.location_id IN (%s) GROUP BY 1
                ), u AS (SELECT code FROM prod UNION SELECT code FROM ks UNION SELECT code FROM os)
                SELECT u.code, (SELECT MAX(name) FROM product WHERE code = u.code) AS name,
                       COALESCE(prod.for_kiosks, 0) AS for_kiosks, COALESCE(prod.for_clients, 0) AS for_clients,
                       COALESCE(ks.q, 0) AS sold_kiosks, COALESCE(os.q, 0) AS sold_online,
                       COALESCE(pt.q, 0) AS pt, COALESCE(kst.q, 0) AS kiosk_stock
                FROM u LEFT JOIN prod ON prod.code = u.code LEFT JOIN ks ON ks.code = u.code
                LEFT JOIN os ON os.code = u.code LEFT JOIN pt ON pt.code = u.code LEFT JOIN kst ON kst.code = u.code
                WHERE u.code IS NOT NULL AND u.code NOT LIKE 'SUM%%' AND u.code NOT LIKE 'FOSS%%'
                ORDER BY GREATEST(COALESCE(prod.for_kiosks, 0), COALESCE(ks.q, 0)) DESC LIMIT %d
                """.formatted(KIOSK_SALE_OK, WAREHOUSE_PT, KIOSKS, PRODUCED_VS_SOLD_ROWS), p,
                (rs, i) -> new ProducedVsSold(rs.getString("code"), rs.getString("name"), rs.getLong("for_kiosks"),
                        rs.getLong("for_clients"), rs.getDouble("sold_kiosks"), rs.getDouble("sold_online"),
                        rs.getLong("pt"), rs.getLong("kiosk_stock")));

        Set<String> codes = new LinkedHashSet<>();
        pvs.forEach(r -> codes.add(r.code()));
        List<MarginRow> margins = codes.isEmpty() ? List.of() : jdbc.query("""
                WITH pr AS (SELECT * FROM product WHERE code IN (:codes)),
                bom_cost AS (
                    SELECT b.product_id, b.id, SUM(bi.quantity * COALESCE(m.unit_cost, m.cost, 0)) AS cost
                    FROM bom b JOIN bom_item bi ON bi.bom_id = b.id JOIN material m ON m.id = bi.material_id
                    WHERE b.product_id IN (SELECT id FROM pr) GROUP BY 1, 2
                ), bom_avg AS (SELECT product_id, AVG(cost) AS cost FROM bom_cost GROUP BY 1),
                leather AS (
                    SELECT v.product_id, AVG(v.qty_per_unit * COALESCE(m.unit_cost, m.cost, 0)) AS cost
                    FROM product_variant_leather v JOIN material m ON m.id = v.leather_material_id
                    WHERE v.product_id IN (SELECT id FROM pr) GROUP BY 1
                ), real_h AS (
                    SELECT product_id,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY actual_duration_minutes::numeric / quantity) / 60.0 AS h
                    FROM task WHERE status = 'COMPLETED' AND completed_at >= :since90Ts
                      AND actual_duration_minutes > 0 AND quantity > 0 AND product_id IN (SELECT id FROM pr)
                    GROUP BY 1 HAVING COUNT(*) >= 3
                )
                SELECT pr.code, pr.name, COALESCE(pr.sale_price, 0) AS price, COALESCE(bom_avg.cost, 0) AS materials,
                       COALESCE(leather.cost, 0) AS leather, pr.prd_time AS std_h, real_h.h AS real_h
                FROM pr LEFT JOIN bom_avg ON bom_avg.product_id = pr.id LEFT JOIN leather ON leather.product_id = pr.id
                LEFT JOIN real_h ON real_h.product_id = pr.id
                WHERE COALESCE(pr.sale_price, 0) > 0
                """, new MapSqlParameterSource(p.getValues()).addValue("codes", codes),
                (rs, i) -> new MarginRow(rs.getString("code"), rs.getString("name"), rs.getDouble("price"),
                        round2(rs.getDouble("materials")), round2(rs.getDouble("leather")),
                        doubleOrNull(rs, "std_h"), roundOrNull(doubleOrNull(rs, "real_h"))));

        // Cobertura del inventario (kioscos + Bodega PT) al ritmo de venta de 90 días, a precio de venta.
        record Stock(String code, String name, long kiosk, long pt, double sold90, double price) {
        }
        List<Stock> stock = jdbc.query("""
                WITH sold AS (
                    SELECT i.product_id, SUM(i.quantity) AS q FROM kiosk_sale_item i JOIN kiosk_sale s ON s.id = i.kiosk_sale_id
                    WHERE %s AND s.sale_date BETWEEN :since90 AND :to GROUP BY 1
                ), kst AS (
                    SELECT product_id, SUM(current_stock) AS q FROM kiosco_stock WHERE location_id IN (%s) GROUP BY 1
                ), pt AS (
                    SELECT product_id, SUM(quantity) AS q FROM product_inventory_location WHERE location_id = %s GROUP BY 1
                )
                SELECT pr.code, pr.name, COALESCE(kst.q, 0) AS kiosk, COALESCE(pt.q, 0) AS pt, COALESCE(sold.q, 0) AS sold,
                       COALESCE(pr.sale_price, 0) AS price
                FROM product pr LEFT JOIN kst ON kst.product_id = pr.id LEFT JOIN pt ON pt.product_id = pr.id
                LEFT JOIN sold ON sold.product_id = pr.id
                WHERE pr.code NOT LIKE 'SUM%%' AND pr.code NOT LIKE 'FOSS%%' AND COALESCE(kst.q, 0) + COALESCE(pt.q, 0) > 0
                """.formatted(KIOSK_SALE_OK, KIOSKS, WAREHOUSE_PT), p,
                (rs, i) -> new Stock(rs.getString("code"), rs.getString("name"), rs.getLong("kiosk"), rs.getLong("pt"),
                        rs.getDouble("sold"), rs.getDouble("price")));

        Map<String, long[]> bandCounts = new LinkedHashMap<>();
        Map<String, Double> bandValues = new HashMap<>();
        for (String key : Arrays.asList("UP_TO_90", "UP_TO_180", "UP_TO_365", "OVER_365", "NO_SALES")) {
            bandCounts.put(key, new long[2]);
            bandValues.put(key, 0d);
        }
        List<SlowRow> slow = new ArrayList<>();
        for (Stock s : stock) {
            long units = s.kiosk() + s.pt();
            double value = units * s.price();
            Double coverage = s.sold90() > 0 ? units / (s.sold90() / 90.0) : null;
            String key = coverage == null ? "NO_SALES" : coverage <= 90 ? "UP_TO_90" : coverage <= 180 ? "UP_TO_180"
                    : coverage <= 365 ? "UP_TO_365" : "OVER_365";
            bandCounts.get(key)[0]++;
            bandCounts.get(key)[1] += units;
            bandValues.merge(key, value, Double::sum);
            if (coverage == null || coverage > 365) {
                slow.add(new SlowRow(s.code(), s.name(), s.kiosk(), s.pt(), s.sold90(), round2(value),
                        coverage == null ? null : round1(coverage)));
            }
        }
        List<InventoryBand> bands = new ArrayList<>();
        bandCounts.forEach((k, v) -> bands.add(new InventoryBand(k, v[0], v[1], round2(bandValues.get(k)))));
        slow.sort(Comparator.comparingDouble(SlowRow::value).reversed());

        return new Profitability(pvs, margins, bands, slow.size() > 12 ? slow.subList(0, 12) : slow);
    }

    // ------------------------------------------------------------------ Cuero

    private List<LeatherRow> leather() {
        return jdbc.query("""
                WITH it AS (
                    SELECT poi.id, poi.product_id, poi.color_id, poi.quantity
                    FROM production_order_item poi JOIN production_order po ON po.id = poi.production_order_id
                    WHERE po.status IN ('PENDING', 'IN_PROGRESS') AND po.order_type <> 'CINCHOS_FOSSILES' AND poi.quantity > 0
                ), started AS (
                    SELECT production_order_item_id AS id, SUM(quantity) AS q FROM task
                    WHERE status <> 'PENDING' AND production_order_item_id IN (SELECT id FROM it) GROUP BY 1
                ), rem AS (
                    SELECT it.product_id, it.color_id, GREATEST(0, it.quantity - COALESCE(started.q, 0)) AS r
                    FROM it LEFT JOIN started ON started.id = it.id
                ), need AS (
                    SELECT v.leather_material_id AS m, SUM(rem.r * v.qty_per_unit) AS need
                    FROM rem JOIN product_variant_leather v ON v.product_id = rem.product_id AND v.color_id = rem.color_id
                    GROUP BY 1
                )
                SELECT m.name, need.need, COALESCE(li.quantity_available, 0) AS available
                FROM need JOIN material m ON m.id = need.m LEFT JOIN leather_inventory li ON li.material_id = need.m
                WHERE need.need > 0
                ORDER BY need.need - COALESCE(li.quantity_available, 0) DESC LIMIT 10
                """, new MapSqlParameterSource(), (rs, i) -> new LeatherRow(rs.getString("name"),
                round1(rs.getDouble("need")), round1(rs.getDouble("available"))));
    }

    // ------------------------------------------------------------------ Cartera

    private Receivables receivables(MapSqlParameterSource p) {
        String balances = """
                WITH e AS (SELECT customer_id, entry_type, entry_date, amount FROM customer_account_entry WHERE status = 'ACTIVE'),
                b AS (
                    SELECT customer_id,
                           SUM(CASE WHEN entry_type IN ('CHARGE', 'CHARGE_ADJUSTMENT') THEN amount ELSE -amount END) AS bal,
                           MAX(entry_date) FILTER (WHERE entry_type = 'PAYMENT') AS last_pay
                    FROM e GROUP BY 1
                )
                """;
        double[] tot = new double[1];
        long[] n = new long[1];
        jdbc.query(balances + "SELECT COALESCE(SUM(bal), 0) AS total, COUNT(*) AS n FROM b WHERE bal > 1",
                p, rs -> {
                    tot[0] = rs.getDouble("total");
                    n[0] = rs.getLong("n");
                });
        List<DebtorRow> top = jdbc.query(balances + """
                SELECT b.customer_id, c.name, b.bal, b.last_pay FROM b JOIN customer c ON c.id = b.customer_id
                WHERE b.bal > 1 ORDER BY b.bal DESC LIMIT 8
                """, p, (rs, i) -> new DebtorRow(rs.getLong("customer_id"), trim(rs.getString("name")),
                round2(rs.getDouble("bal")), localDate(rs, "last_pay")));
        Double collected = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM customer_account_entry
                WHERE status = 'ACTIVE' AND entry_type = 'PAYMENT' AND entry_date BETWEEN :from AND :to
                """, p, Double.class);
        Boolean credit = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM customer WHERE COALESCE(credit_days, 0) > 0)", p, Boolean.class);
        return new Receivables(round2(tot[0]), n[0], round2(collected == null ? 0 : collected),
                Boolean.TRUE.equals(credit), top);
    }

    // ------------------------------------------------------------------ Publicidad

    private List<AdMonth> ads(LocalDate today) {
        YearMonth first = YearMonth.from(today).minusMonths(2);
        MapSqlParameterSource p = new MapSqlParameterSource("start", Date.valueOf(first.atDay(1)))
                .addValue("today", Date.valueOf(today));
        Map<YearMonth, double[]> months = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            months.put(first.plusMonths(i), new double[2]);
        }
        jdbc.query("""
                SELECT date_trunc('month', spend_date)::date AS m, SUM(amount) AS v FROM online_ad_spend
                WHERE spend_date BETWEEN :start AND :today GROUP BY 1
                """, p, rs -> {
            double[] row = months.get(YearMonth.from(rs.getDate("m").toLocalDate()));
            if (row != null) row[0] = rs.getDouble("v");
        });
        jdbc.query("""
                SELECT date_trunc('month', sale_date)::date AS m, SUM(total_amount) AS v FROM online_sale
                WHERE sale_date BETWEEN :start AND :today AND COALESCE(status, '') NOT IN ('ANULADA', 'DEVOLUCION') GROUP BY 1
                """, p, rs -> {
            double[] row = months.get(YearMonth.from(rs.getDate("m").toLocalDate()));
            if (row != null) row[1] = rs.getDouble("v");
        });
        List<AdMonth> out = new ArrayList<>();
        months.forEach((ym, v) -> out.add(new AdMonth(ym.getYear(), ym.getMonthValue(), round2(v[0]), round2(v[1]))));
        return out;
    }

    // ------------------------------------------------------------------ utilidades

    private static List<Point> weekSeries(Map<LocalDate, Double> byWeek, LocalDate today) {
        LocalDate current = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Point> out = new ArrayList<>();
        for (int i = WEEKS - 1; i >= 0; i--) {
            LocalDate wk = current.minusWeeks(i);
            out.add(new Point(wk, round2(byWeek.getOrDefault(wk, 0d)), i == 0));
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim().replaceAll("\\s{2,}", " ");
    }

    private static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double doubleOrNull(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static double num(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Double roundOrNull(Double v) {
        return v == null ? null : round2(v);
    }
}
