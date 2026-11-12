package com.fossiles.fossilescorebackend.application.service.promotion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionRuleEngineTest {

    /** Viernes 27-nov-2026, 19:00. */
    private static final PromotionRuleEngine.Context FRIDAY =
            new PromotionRuleEngine.Context(LocalDate.of(2026, 11, 27), LocalTime.of(19, 0), "EFECTIVO");

    private static List<PromotionRuleEngine.Line> lines(double... prices) {
        List<PromotionRuleEngine.Line> list = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            list.add(line(i, 10L + i, 1L, prices[i], 1, true));
        }
        return list;
    }

    private static PromotionRuleEngine.Line line(int index, Long productId, Long categoryId, double price, int qty, boolean eligible) {
        return new PromotionRuleEngine.Line(index, productId, categoryId, "UNISEX", null, null,
                BigDecimal.valueOf(qty), BigDecimal.valueOf(price), eligible);
    }

    private static PromotionRuleConfig position(String order, int groupSize, String incomplete, Double remainder, double... pcts) {
        List<BigDecimal> positions = new ArrayList<>();
        for (double p : pcts) {
            positions.add(BigDecimal.valueOf(p));
        }
        PromotionRuleConfig config = new PromotionRuleConfig();
        config.getConditions().setMinQty(2);
        config.getAction().setType("POSITION");
        config.getAction().setOrder(order);
        config.getAction().setGroupSize(groupSize);
        config.getAction().setPositions(positions);
        config.getAction().setIncompleteGroup(incomplete);
        config.getAction().setRemainderPercent(remainder != null ? BigDecimal.valueOf(remainder) : null);
        return config;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "esperado " + expected + " y salió " + actual);
    }

    @Test
    void blackFridayEnPares_laCara15_laBarata30_sobrante15() {
        PromotionRuleConfig config = position("DESC", 2, "REMAINDER", 15.0, 15, 30);
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, lines(400, 300, 200), FRIDAY);

        assertTrue(result.applies());
        assertMoney("60.00", result.lineDiscounts().get(0));
        assertMoney("90.00", result.lineDiscounts().get(1));
        assertMoney("30.00", result.lineDiscounts().get(2));
        assertMoney("180.00", result.discount());
        assertEquals("Pos. 2 · 30%", result.lineNotes().get(1));
    }

    @Test
    void blackFridaySoloLaMasBarataAl30_restoAl15() {
        PromotionRuleConfig config = position("ASC", 0, null, 15.0, 30);
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, lines(400, 300, 200), FRIDAY);

        assertMoney("60.00", result.lineDiscounts().get(0));
        assertMoney("45.00", result.lineDiscounts().get(1));
        assertMoney("60.00", result.lineDiscounts().get(2));
        assertMoney("165.00", result.discount());
    }

    /** Guía Black Friday: 1 pieza 15%; desde 2, la más barata 30% y el resto 15%. */
    private static PromotionRuleConfig blackFridayGuia() {
        PromotionRuleConfig config = position("ASC", 0, null, 15.0, 30);
        config.getConditions().setMinQty(null);
        config.getAction().setPositionsMinQty(2);
        return config;
    }

    @Test
    void guiaBlackFriday_unArticulo15() {
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(blackFridayGuia(), lines(500), FRIDAY);

        assertTrue(result.applies());
        assertMoney("75.00", result.discount()); // paga Q425
        assertEquals("15%", result.lineNotes().get(0));
    }

    @Test
    void guiaBlackFriday_dosArticulos() {
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(blackFridayGuia(), lines(500, 300), FRIDAY);

        assertMoney("75.00", result.lineDiscounts().get(0));
        assertMoney("90.00", result.lineDiscounts().get(1));
        assertMoney("165.00", result.discount()); // paga Q635
    }

    @Test
    void guiaBlackFriday_tresArticulos() {
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(blackFridayGuia(), lines(500, 300, 200), FRIDAY);

        assertMoney("75.00", result.lineDiscounts().get(0));
        assertMoney("45.00", result.lineDiscounts().get(1));
        assertMoney("60.00", result.lineDiscounts().get(2));
        assertMoney("180.00", result.discount()); // paga Q820 (la guía dice Q805 por error de suma)
    }

    @Test
    void guiaBlackFriday_cuatroArticulos_el30VaAlDe200() {
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(blackFridayGuia(), lines(500, 300, 200, 350), FRIDAY);

        assertMoney("75.00", result.lineDiscounts().get(0));
        assertMoney("45.00", result.lineDiscounts().get(1));
        assertMoney("60.00", result.lineDiscounts().get(2));
        assertMoney("52.50", result.lineDiscounts().get(3));
        assertMoney("232.50", result.discount()); // paga Q1,117.50
    }

    @Test
    void dosPiezas_mismaLineaConCantidad2() {
        PromotionRuleConfig config = position("DESC", 2, "REMAINDER", 15.0, 15, 30);
        List<PromotionRuleEngine.Line> cart = List.of(line(0, 1L, 1L, 250, 2, true));
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, cart, FRIDAY);

        // 250 × 15% + 250 × 30% = 37.50 + 75
        assertMoney("112.50", result.discount());
        assertEquals("Pos. 1 · 15% + Pos. 2 · 30%", result.lineNotes().get(0));
    }

    @Test
    void unaSolaPieza_noAplicaPorMinimo() {
        PromotionRuleConfig config = position("DESC", 2, "REMAINDER", 15.0, 15, 30);
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, lines(400), FRIDAY);

        assertFalse(result.applies());
        assertMoney("0", result.discount());
        assertTrue(result.reasons().get(0).startsWith("Necesita al menos 2 piezas"));
    }

    @Test
    void empaqueNoEntraNiCuentaParaElMinimo() {
        PromotionRuleConfig config = position("DESC", 2, "REMAINDER", 15.0, 15, 30);
        List<PromotionRuleEngine.Line> cart = List.of(
                line(0, 1L, 1L, 400, 1, true),
                line(1, 2L, 9L, 5, 1, false));
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, cart, FRIDAY);

        assertFalse(result.applies());
        assertMoney("0", result.lineDiscounts().get(1));
    }

    @Test
    void dosPorUno_comoPosicion() {
        PromotionRuleConfig config = position("DESC", 2, null, null, 0, 100);
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, lines(300, 200, 100), FRIDAY);

        assertMoney("200.00", result.discount());
        assertMoney("0", result.lineDiscounts().get(2));
    }

    @Test
    void escalonesPorCantidad() {
        PromotionRuleConfig config = new PromotionRuleConfig();
        config.getAction().setType("QTY_TIERS");
        config.getAction().setQtyTiers(List.of(
                new PromotionRuleConfig.QtyTier(2, BigDecimal.TEN),
                new PromotionRuleConfig.QtyTier(3, BigDecimal.valueOf(15))));
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, lines(100, 100, 100), FRIDAY);

        assertMoney("45.00", result.discount());
    }

    @Test
    void compraCinchoYBilleteraAl30() {
        PromotionRuleConfig config = new PromotionRuleConfig();
        config.getFilter().setCategoryIds(List.of(2L));
        PromotionRuleConfig.Filter buy = new PromotionRuleConfig.Filter();
        buy.setCategoryIds(List.of(1L));
        config.getAction().setType("BUY_GET");
        config.getAction().setBuyFilter(buy);
        config.getAction().setBuyQty(1);
        config.getAction().setGetQty(1);
        config.getAction().setGetPercent(BigDecimal.valueOf(30));
        List<PromotionRuleEngine.Line> cart = List.of(
                line(0, 1L, 1L, 300, 1, true),
                line(1, 2L, 2L, 200, 1, true));
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, cart, FRIDAY);

        assertMoney("0", result.lineDiscounts().get(0));
        assertMoney("60.00", result.lineDiscounts().get(1));
    }

    @Test
    void topeDeDescuento() {
        PromotionRuleConfig config = new PromotionRuleConfig();
        config.getAction().setType("PERCENT");
        config.getAction().setValue(BigDecimal.valueOf(50));
        config.getLimits().setMaxDiscount(BigDecimal.valueOf(100));
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(config, lines(300, 100), FRIDAY);

        assertMoney("100.00", result.discount());
    }

    @Test
    void soloConTarjeta_sinMetodoElegidoNoAplica() {
        PromotionRuleConfig config = new PromotionRuleConfig();
        config.getConditions().setPaymentMethods(List.of("TARJETA"));
        config.getAction().setType("PERCENT");
        config.getAction().setValue(BigDecimal.TEN);
        PromotionRuleEngine.Context noPayment =
                new PromotionRuleEngine.Context(FRIDAY.date(), FRIDAY.time(), null);

        assertFalse(PromotionRuleEngine.evaluate(config, lines(100), noPayment).applies());
        assertFalse(PromotionRuleEngine.evaluate(config, lines(100), FRIDAY).applies());
        PromotionRuleEngine.Context card = new PromotionRuleEngine.Context(FRIDAY.date(), FRIDAY.time(), "Tarjeta");
        assertMoney("10.00", PromotionRuleEngine.evaluate(config, lines(100), card).discount());
    }

    @Test
    void diaYHorario() {
        PromotionRuleConfig config = new PromotionRuleConfig();
        config.getConditions().setDaysOfWeek(List.of(5));
        config.getConditions().setTimeFrom("18:00");
        config.getConditions().setTimeTo("22:00");
        config.getAction().setType("PERCENT");
        config.getAction().setValue(BigDecimal.TEN);

        assertTrue(PromotionRuleEngine.evaluate(config, lines(100), FRIDAY).applies());
        PromotionRuleEngine.Context late = new PromotionRuleEngine.Context(FRIDAY.date(), LocalTime.of(22, 30), "EFECTIVO");
        assertFalse(PromotionRuleEngine.evaluate(config, lines(100), late).applies());
        PromotionRuleEngine.Context saturday = new PromotionRuleEngine.Context(LocalDate.of(2026, 11, 28), LocalTime.of(19, 0), "EFECTIVO");
        assertFalse(PromotionRuleEngine.evaluate(config, lines(100), saturday).applies());
    }

    @Test
    void jsonIdaYVuelta() {
        PromotionRuleConfig config = position("DESC", 2, "REMAINDER", 15.0, 15, 30);
        PromotionRuleConfig back = PromotionRuleEngine.fromJson(PromotionRuleEngine.toJson(config));
        PromotionRuleEngine.Result result = PromotionRuleEngine.evaluate(back, lines(400, 300, 200), FRIDAY);

        assertMoney("180.00", result.discount());
        assertTrue(PromotionRuleEngine.validate(back).isEmpty());
    }

    @Test
    void validacionPosicionSinDescuento() {
        PromotionRuleConfig config = position("DESC", 2, null, null, 0, 0);
        assertFalse(PromotionRuleEngine.validate(config).isEmpty());
    }
}
