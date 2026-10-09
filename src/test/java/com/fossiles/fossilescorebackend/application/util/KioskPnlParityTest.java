package com.fossiles.fossilescorebackend.application.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paridad con los 12 Excel de 2025: recalcula con {@link KioskPnlCalculator} y compara contra los valores
 * cacheados de la hoja (fixture generado desde Documentacion/reportesventas con openpyxl, data_only=True).
 */
class KioskPnlParityTest {

    private static final double TOL_AMOUNT = 0.01;
    private static final double TOL_MARGIN = 0.0001;

    @Test
    void recalculatedPnlMatchesSheetForEveryKioskMonth() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/kiosk-financials/pnl-control-2025.json")) {
            assertThat(in).as("fixture pnl-control-2025.json").isNotNull();
            root = new ObjectMapper().readTree(in);
        }

        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (JsonNode month : root.get("months")) {
            int m = month.get("month").asInt();
            for (JsonNode k : month.get("kiosks")) {
                String label = "m" + m + " " + k.get("name").asText();
                BigDecimal sales = dec(k.get("sales"));
                JsonNode rn = k.get("rates");
                KioskPnlCalculator.Rates rates = new KioskPnlCalculator.Rates(
                        dec(rn.get("productCostPct")), dec(rn.get("salesCommissionPct")),
                        dec(rn.get("cardCommissionPct")), dec(rn.get("taxPct")));
                Map<String, BigDecimal> fixed = new LinkedHashMap<>();
                k.get("fixed").fields().forEachRemaining(e -> fixed.put(e.getKey(), dec(e.getValue())));

                KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(sales, rates, fixed, 30);
                JsonNode sheet = k.get("sheet");
                compared++;

                check(mismatches, label, "totalCost", r.totalCost(), sheet.get("totalCost"), TOL_AMOUNT);
                check(mismatches, label, "difference", r.difference(), sheet.get("difference"), TOL_AMOUNT);
                check(mismatches, label, "margin", r.margin(), sheet.get("margin"), TOL_MARGIN);
                check(mismatches, label, "breakEven", r.breakEven(), sheet.get("breakEven"), TOL_AMOUNT);
            }
        }

        assertThat(compared).as("kiosk-months compared").isGreaterThan(400);
        if (!mismatches.isEmpty()) {
            System.out.println("PARITY MISMATCHES (" + mismatches.size() + " of " + compared + " kiosk-months):");
            mismatches.forEach(System.out::println);
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void january2025TotalsMatchControlValues() throws Exception {
        JsonNode jan;
        try (InputStream in = getClass().getResourceAsStream("/kiosk-financials/pnl-control-2025.json")) {
            jan = new ObjectMapper().readTree(in).get("months").get(0);
        }
        assertThat(jan.get("month").asInt()).isEqualTo(1);

        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal diff = BigDecimal.ZERO;
        for (JsonNode k : jan.get("kiosks")) {
            JsonNode rn = k.get("rates");
            Map<String, BigDecimal> fixed = new LinkedHashMap<>();
            k.get("fixed").fields().forEachRemaining(e -> fixed.put(e.getKey(), dec(e.getValue())));
            KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(dec(k.get("sales")),
                    new KioskPnlCalculator.Rates(dec(rn.get("productCostPct")), dec(rn.get("salesCommissionPct")),
                            dec(rn.get("cardCommissionPct")), dec(rn.get("taxPct"))),
                    fixed, 31);
            sales = sales.add(r.sales());
            cost = cost.add(r.totalCost());
            diff = diff.add(r.difference());
        }

        assertThat(KioskPnlCalculator.round2(sales)).isEqualByComparingTo("1090923.30");
        assertThat(KioskPnlCalculator.round2(cost)).isEqualByComparingTo("752652.37");
        assertThat(KioskPnlCalculator.round2(diff)).isEqualByComparingTo("338270.93");
        assertThat(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(diff, sales))).isEqualByComparingTo("0.3101");
    }

    private static void check(List<String> out, String label, String field, BigDecimal actual, JsonNode expected, double tol) {
        if (expected == null || expected.isNull()) {
            return; // celda de la hoja no numerica (p.ej. #DIV/0!): nada que comparar
        }
        double exp = expected.asDouble();
        if (actual == null) {
            out.add(label + " " + field + ": calculator=null sheet=" + exp);
            return;
        }
        double act = actual.doubleValue();
        if (Math.abs(act - exp) > tol) {
            out.add(label + " " + field + ": calculator=" + act + " sheet=" + exp);
        }
    }

    private static BigDecimal dec(JsonNode n) {
        return (n == null || n.isNull()) ? null : new BigDecimal(n.asText());
    }
}
