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
import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse.Stages;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsPnlResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.LineProgress;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.OrderProgress;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
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
import java.time.temporal.ChronoUnit;
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
    private final ProductionOrderProgressService progressService;

    public ExecutiveDashboardResponse get(Integer days, LocalDate finFrom, LocalDate finTo, boolean refresh) {
        guard.assertCanView();
        int window = days == null || days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate today = GuatemalaDateTime.today();
        ExecutiveDashboardResponse base = cache.get(
                SalesDashboardCache.key("executive", today.minusDays(window - 1L), today, null), refresh,
                () -> build(window, today));
        // Finanzas va aparte: depende del rango elegido y el P&L revisa los permisos de cada usuario.
        Finance finance = finance(today, finFrom, finTo);
        return new ExecutiveDashboardResponse(base.from(), base.to(), base.days(), base.generatedAt(), base.production(),
                base.orders(), base.kioskSales(), finance, base.profitability(), base.leather(), base.receivables(),
                base.ads());
    }

    /** Solo la parte de Finanzas, para cambiar el rango sin recargar todo el tablero. */
    public Finance finance(LocalDate from, LocalDate to) {
        guard.assertCanView();
        return finance(GuatemalaDateTime.today(), from, to);
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
                .addValue("today", Date.valueOf(today))
                // Ventas de kioscos con el día cerrado: la misma ventana, pero terminando ayer.
                .addValue("salesFrom", Date.valueOf(from.minusDays(1)))
                .addValue("salesTo", Date.valueOf(today.minusDays(1)))
                .addValue("salesPrevFrom", Date.valueOf(from.minusDays(1L + window)))
                .addValue("salesPrevTo", Date.valueOf(from.minusDays(2)));

        Profitability profitability = profitability(p);
        ProductionOrderProgressResponse progress = progressService.report();
        return new ExecutiveDashboardResponse(
                from, today, window, GuatemalaDateTime.now(),
                production(p, today, progress),
                orders(progress),
                kioskSales(p, today),
                null,
                profitability,
                leather(),
                receivables(p),
                ads(today));
    }

    // ------------------------------------------------------------------ Producción

    private Production production(MapSqlParameterSource p, LocalDate today, ProductionOrderProgressResponse progress) {
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

        // Horas estándar terminadas por semana: la capacidad real de la planta medida en el mismo «idioma» que las
        // horas que faltan (piezas × horas por pieza del producto).
        Map<LocalDate, Double> weeklyHours = new HashMap<>();
        jdbc.query("""
                SELECT date_trunc('week', t.completed_at)::date AS wk,
                       SUM(t.quantity * COALESCE(NULLIF(pr.prd_time, 0), :defaultHours)) AS h
                FROM task t LEFT JOIN product pr ON pr.id = t.product_id
                WHERE t.status = 'COMPLETED' AND t.completed_at >= :weeksFrom GROUP BY 1
                """, new MapSqlParameterSource(p.getValues())
                .addValue("defaultHours", ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT), rs -> {
            weeklyHours.put(rs.getDate("wk").toLocalDate(), rs.getDouble("h"));
        });
        List<Point> weeksHours = weekSeries(weeklyHours, today);
        double avgWeekHours = weeksHours.stream().filter(w -> !w.current()).skip(Math.max(0, weeksHours.size() - 5L))
                .mapToDouble(Point::value).average().orElse(0);

        Stages backlog = stages(progress.getTotals() == null ? null : progress.getTotals().getStages());
        double hoursLeft = orderList(progress).stream().mapToDouble(o -> hoursLeft(o.getLines())).sum();

        return new Production(totals[0], totals[1], round1(minutes[0] / 60.0), totals[2], startedOn[0], all[0], all[1],
                weeks, round1(avgWeek), weeksHours, round1(avgWeekHours), desks, productTime, backlog,
                Math.max(0, backlog.units() - backlog.done()), round1(hoursLeft));
    }

    // ------------------------------------------------------------------ Órdenes

    /** Mismo cálculo que «Avance por orden» del Dashboard de Producción, para que las cifras cuadren. */
    private Orders orders(ProductionOrderProgressResponse progress) {
        List<OrderProgress> list = orderList(progress);
        long pending = list.stream().filter(o -> "PENDING".equals(o.getStatus())).count();
        long inProgress = list.stream().filter(o -> "IN_PROGRESS".equals(o.getStatus())).count();
        long inQa = list.stream().filter(o -> "IN_QA".equals(o.getStatus())).count();
        ProductionOrderProgressResponse.Totals t = progress.getTotals();
        long late = t != null && t.getLateOrders() != null ? t.getLateOrders() : list.stream().filter(o -> Boolean.TRUE.equals(o.getLate())).count();
        long working = t != null && t.getOrdersWorkingNow() != null ? t.getOrdersWorkingNow() : 0;

        Map<String, Long> types = new LinkedHashMap<>();
        double hours = 0;
        double lateHours = 0;
        List<OrderRow> rows = new ArrayList<>();
        for (OrderProgress o : list) {
            types.merge(o.getOrderType() == null ? "OTRO" : o.getOrderType(), 1L, Long::sum);
            double h = hoursLeft(o.getLines());
            hours += h;
            if (Boolean.TRUE.equals(o.getLate())) {
                lateHours += h;
            }
            Stages s = stages(o.getStages());
            if (s.units() > 0) {
                rows.add(new OrderRow(o.getOrderId(), o.getCode(), o.getOrderType(), o.getStatus(), trim(o.getCustomerName()),
                        o.getDeliveryDate(), Boolean.TRUE.equals(o.getLate()), s, round1(h)));
            }
        }
        rows.sort(Comparator.comparingLong((OrderRow r) -> r.stages().units() - r.stages().done()).reversed());
        List<TypeCount> byType = new ArrayList<>();
        types.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> byType.add(new TypeCount(e.getKey(), e.getValue())));
        return new Orders(list.size(), pending, inProgress, inQa, late, working, round1(hours), round1(lateHours), byType,
                rows.size() > 80 ? rows.subList(0, 80) : rows);
    }

    private static List<OrderProgress> orderList(ProductionOrderProgressResponse progress) {
        return progress == null || progress.getOrders() == null ? List.of() : progress.getOrders();
    }

    private static Stages stages(ProductionOrderProgressResponse.Stages s) {
        if (s == null) {
            return new Stages(0, 0, 0, 0, 0, 0);
        }
        return new Stages(nz(s.getUnits()), nz(s.getNoTask()), nz(s.getPending()), nz(s.getInProgress()),
                nz(s.getWarehouse()), nz(s.getDone()));
    }

    /** Igual que el front de Avance por orden: (pedidas − terminadas) × horas por pieza de la línea. */
    static double hoursLeft(List<LineProgress> lines) {
        if (lines == null) {
            return 0;
        }
        double h = 0;
        for (LineProgress l : lines) {
            ProductionOrderProgressResponse.Stages s = l.getStages();
            long missing = s == null ? 0 : Math.max(0, nz(s.getUnits()) - nz(s.getDone()));
            double perUnit = l.getPrdTimePerUnit() != null ? l.getPrdTimePerUnit()
                    : ProductionPlanningConstants.DEFAULT_PRD_TIME_PER_UNIT;
            h += missing * perUnit;
        }
        return h;
    }

    private static long nz(Integer v) {
        return v == null ? 0 : v;
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
                    WHERE %s AND s.sale_date BETWEEN :salesFrom AND :salesTo AND COALESCE(i.product_code, '') NOT LIKE 'SUM%%'
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
                LEFT JOIN kiosk_sale s ON s.kiosk_location_id = l.id AND %s AND s.sale_date BETWEEN :salesFrom AND :salesTo
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
                WHERE %s AND s.sale_date BETWEEN :salesPrevFrom AND :salesPrevTo AND s.kiosk_location_id IN (%s)
                """.formatted(KIOSK_SALE_OK, KIOSKS), p, Double.class);

        Map<LocalDate, Double> weekly = new HashMap<>();
        jdbc.query("""
                SELECT date_trunc('week', s.sale_date)::date AS wk, SUM(s.total_amount) AS amount FROM kiosk_sale s
                WHERE %s AND s.sale_date >= :weeksFrom AND s.sale_date <= :salesTo AND s.kiosk_location_id IN (%s) GROUP BY 1
                """.formatted(KIOSK_SALE_OK, KIOSKS), p, rs -> {
            weekly.put(rs.getDate("wk").toLocalDate(), rs.getDouble("amount"));
        });

        List<TopProduct> top = jdbc.query("""
                WITH sold AS (
                    SELECT i.product_id, SUM(i.quantity) AS q, SUM(i.line_total) AS amount
                    FROM kiosk_sale_item i JOIN kiosk_sale s ON s.id = i.kiosk_sale_id
                    WHERE %s AND s.sale_date BETWEEN :salesFrom AND :salesTo
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

        return new KioskSales(((Date) p.getValue("salesFrom")).toLocalDate(), today.minusDays(1), round2(sales),
                round2(previous == null ? 0 : previous), tickets, units,
                weekSeries(weekly, today), kiosks, top);
    }

    // ------------------------------------------------------------------ Finanzas

    /**
     * Finanzas de kioscos en un rango. El día de hoy nunca cuenta (la venta se toma con el día cerrado): si el rango
     * llega a hoy o después, termina ayer. Sin fechas = del 1 del mes hasta ayer (el 1 del mes, el mes anterior completo).
     */
    private Finance finance(LocalDate today, LocalDate fromParam, LocalDate toParam) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate to = toParam == null ? yesterday : toParam;
        boolean capped = false;
        if (to.isAfter(yesterday)) {
            to = yesterday;
            capped = toParam != null;
        }
        LocalDate from = fromParam == null ? to.withDayOfMonth(1) : fromParam;
        if (from.isAfter(to)) {
            from = to.withDayOfMonth(1);
        }
        if (from.isBefore(to.minusDays(MAX_DAYS))) {
            from = to.minusDays(MAX_DAYS);
        }
        YearMonth ym = YearMonth.from(to);
        YearMonth current = YearMonth.from(today);
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        // Para proyectar al cierre: solo si el rango empieza el 1 del mes; mes completo = 1, mes abierto = días/mes.
        Double factor = null;
        if (from.equals(ym.atDay(1))) {
            if (to.equals(ym.atEndOfMonth())) {
                factor = 1.0;
            } else if (ym.equals(current)) {
                factor = (double) ym.lengthOfMonth() / days;
            }
        }

        // Meta de cada mes que toca el rango, prorrateada por los días del rango dentro de ese mes.
        Map<YearMonth, Double> monthShare = new LinkedHashMap<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(ym); m = m.plusMonths(1)) {
            LocalDate a = from.isAfter(m.atDay(1)) ? from : m.atDay(1);
            LocalDate b = to.isBefore(m.atEndOfMonth()) ? to : m.atEndOfMonth();
            long overlap = ChronoUnit.DAYS.between(a, b) + 1;
            monthShare.put(m, (double) overlap / m.lengthOfMonth());
        }
        YearMonth firstMonth = YearMonth.from(from);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("a", firstMonth.getYear() * 100 + firstMonth.getMonthValue())
                .addValue("b", ym.getYear() * 100 + ym.getMonthValue())
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
        Map<Long, double[]> goalByKiosk = new HashMap<>();
        jdbc.query("""
                SELECT kiosk_location_id AS id, goal_year, goal_month, SUM(goal_amount) AS goal FROM kiosk_monthly_goal
                WHERE goal_year * 100 + goal_month BETWEEN :a AND :b GROUP BY 1, 2, 3
                """, p, rs -> {
            YearMonth m = YearMonth.of(rs.getInt("goal_year"), rs.getInt("goal_month"));
            double goal = rs.getDouble("goal");
            double[] g = goalByKiosk.computeIfAbsent(rs.getLong("id"), k -> new double[2]);
            g[0] += goal * monthShare.getOrDefault(m, 0d);
            if (m.equals(ym)) {
                g[1] = goal;
            }
        });
        List<GoalRow> goals = jdbc.query("""
                WITH k AS (%s),
                v AS (SELECT s.kiosk_location_id AS id, SUM(s.total_amount) AS sales FROM kiosk_sale s
                      WHERE %s AND s.sale_date BETWEEN :from AND :to GROUP BY 1)
                SELECT l.id, l.name, COALESCE(v.sales, 0) AS sales
                FROM locations l LEFT JOIN v ON v.id = l.id
                WHERE l.id IN (SELECT id FROM k) ORDER BY 3 DESC
                """.formatted(KIOSKS, KIOSK_SALE_OK), p, (rs, i) -> {
            double[] g = goalByKiosk.getOrDefault(rs.getLong("id"), new double[2]);
            return new GoalRow(rs.getLong("id"), rs.getString("name"), round2(g[1]), round2(g[0]),
                    round2(rs.getDouble("sales")));
        });
        double sales = goals.stream().mapToDouble(GoalRow::sales).sum();

        // Mismo rango del año pasado (histórico importado + POS) y la temporada: el mes del rango y el siguiente.
        double lastYearSame = sumHistory(from.minusYears(1), to.minusYears(1));
        YearMonth lyMonth = ym.minusYears(1);
        double lastYearMonth = sumHistory(lyMonth.atDay(1), lyMonth.atEndOfMonth());
        YearMonth lyNext = lyMonth.plusMonths(1);
        double lastYearNext = sumHistory(lyNext.atDay(1), lyNext.atEndOfMonth());

        // P&L oficial: el mes del rango si ya cerró; si no, el mes cerrado anterior.
        YearMonth pnlMonth = ym.isBefore(current) ? ym : current.minusMonths(1);
        List<PnlRow> pnl;
        String unavailable = null;
        try {
            pnl = pnl(pnlMonth);
        } catch (RuntimeException e) {
            log.info("Tablero gerencial sin P&L de Finanzas: {}", e.getMessage());
            unavailable = "No se pudo leer el P&L de Finanzas por kiosco (" + e.getMessage() + ").";
            pnl = List.of();
        }
        return new Finance(from, to, capped, days, ym.getYear(), ym.getMonthValue(), ym.lengthOfMonth(), factor, goals,
                pnlMonth.getYear(), pnlMonth.getMonthValue(), pnl, unavailable, round2(sales), round2(lastYearSame),
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

    /** P&amp;L oficial de Finanzas por kiosco del mes. Se lee por usuario porque Finanzas valida sus propios permisos. */
    private List<PnlRow> pnl(YearMonth month) {
        Map<Long, Long> siteToLocation = new HashMap<>();
        jdbc.query("SELECT id, location_id FROM kiosk_site", new MapSqlParameterSource(), rs -> {
            long loc = rs.getLong("location_id");
            siteToLocation.put(rs.getLong("id"), rs.wasNull() ? null : loc);
        });
        KioskFinancialsPnlResponse pnl;
        try {
            pnl = financialsReportService.getPnl(month.getYear(), month.getMonthValue(), null);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        List<PnlRow> rows = new ArrayList<>();
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
        return rows;
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
