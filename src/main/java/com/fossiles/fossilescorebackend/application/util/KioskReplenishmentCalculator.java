package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reposición de kioscos por categoría A/B/C (RF-INV-05 / RF-POS-03): calcula mínimo y máximo de cada línea
 * (producto + color + talla + herraje) a partir de la venta real del kiosco, decide cuánto enviar y reparte el
 * stock disponible de bodega con prioridad A &gt; B &gt; C.
 * <p>
 * Reglas (Documento General, 4.2 Reglas para Quioscos):
 * <ul>
 *   <li><b>A</b>: siempre se rellena hasta el máximo, aunque tenga stock.</li>
 *   <li><b>B</b>: se rellena hasta el máximo si está por debajo del mínimo, o si es línea de alta venta / artículo base.</li>
 *   <li><b>C</b>: solo se envía lo necesario para llegar al mínimo.</li>
 * </ul>
 * El mínimo cubre {@link #REVIEW_DAYS} días de venta y el máximo los días de cobertura de la categoría, siempre
 * dentro del piso/tope de unidades de la categoría. Solo se calculan líneas con venta: con datos de prod (nov-2026)
 * un kiosco maneja 200–350 líneas y vende ~1 unidad por línea en 60 días, así que reponer líneas sin venta pedía
 * más del doble de lo que el kiosco vende.
 */
public final class KioskReplenishmentCalculator {

    /** Días entre una reposición y la siguiente: el mínimo debe aguantar ese periodo. */
    public static final int REVIEW_DAYS = 7;

    /** Percentil de venta diaria (dentro del kiosco) a partir del cual una línea es de alta venta. */
    public static final double TOP_SELLER_PERCENTILE = 0.80;

    /** Fracción de kioscos que deben vender un producto para considerarlo artículo base. */
    public static final double BASE_PRODUCT_KIOSK_SHARE = 0.60;

    private KioskReplenishmentCalculator() {
    }

    public enum Category {
        A(21, 1, 8, 1.0),
        B(14, 1, 5, 0.8),
        C(7, 1, 2, 0.6);

        /** Días de venta que debe cubrir el máximo. */
        public final int coverageDays;
        /** Piso de unidades por línea. */
        public final int minUnits;
        /** Tope de unidades por línea. */
        public final int maxUnits;
        /** Fracción de la venta semanal del kiosco que se le manda por envío. */
        public final double budgetFactor;

        Category(int coverageDays, int minUnits, int maxUnits, double budgetFactor) {
            this.coverageDays = coverageDays;
            this.minUnits = minUnits;
            this.maxUnits = maxUnits;
            this.budgetFactor = budgetFactor;
        }

        public static Category parse(String raw) {
            if (raw == null) {
                return null;
            }
            String v = raw.trim().toUpperCase();
            for (Category c : values()) {
                if (c.name().equals(v)) {
                    return c;
                }
            }
            return null;
        }
    }

    /**
     * Una línea de un kiosco: existencia actual, lo que ya va en camino (envíos sin recibir) y lo vendido en la
     * ventana de análisis.
     */
    public record LineInput(int stock, int inTransit, BigDecimal sold, boolean topSeller, boolean base) {
    }

    public record LinePlan(BigDecimal dailyRate, int min, int max, int need, String reason) {
    }

    public static LinePlan plan(Category category, int windowDays, LineInput in) {
        BigDecimal sold = in.sold() == null || in.sold().signum() < 0 ? BigDecimal.ZERO : in.sold();
        BigDecimal rate = windowDays <= 0
                ? BigDecimal.ZERO
                : sold.divide(BigDecimal.valueOf(windowDays), 4, RoundingMode.HALF_UP);

        int max = clamp(ceil(rate.multiply(BigDecimal.valueOf(category.coverageDays))),
                category.minUnits, category.maxUnits);
        int min = clamp(ceil(rate.multiply(BigDecimal.valueOf(REVIEW_DAYS))), category.minUnits, max);
        int available = Math.max(0, in.stock()) + Math.max(0, in.inTransit());

        int need = 0;
        String reason = null;
        switch (category) {
            case A -> {
                if (available < max) {
                    need = max - available;
                    reason = available < min ? "Debajo del mínimo" : "Rellenar al máximo (A)";
                }
            }
            case B -> {
                if (available < min) {
                    need = max - available;
                    reason = "Debajo del mínimo";
                } else if ((in.topSeller() || in.base()) && available < max) {
                    need = max - available;
                    reason = in.topSeller() ? "Alta venta" : "Artículo base";
                }
            }
            case C -> {
                if (available < min) {
                    need = min - available;
                    reason = "Debajo del mínimo";
                }
            }
        }
        return new LinePlan(rate, min, max, Math.max(0, need), reason);
    }

    /**
     * Venta diaria a partir de la cual una línea es de alta venta dentro de su kiosco (percentil
     * {@link #TOP_SELLER_PERCENTILE} de las líneas con venta). {@code null} si el kiosco no tiene venta.
     */
    public static BigDecimal topSellerThreshold(List<BigDecimal> dailyRates) {
        List<BigDecimal> positive = new ArrayList<>();
        for (BigDecimal r : dailyRates) {
            if (r != null && r.signum() > 0) {
                positive.add(r);
            }
        }
        if (positive.isEmpty()) {
            return null;
        }
        positive.sort(Comparator.naturalOrder());
        int idx = (int) Math.floor(TOP_SELLER_PERCENTILE * (positive.size() - 1));
        return positive.get(idx);
    }

    /**
     * Categoría para los kioscos sin categoría asignada en Finanzas: por unidades vendidas, el tercio de arriba es
     * A, el de en medio B y el resto C (se ordena contra todos los kioscos, con o sin categoría).
     */
    public static Map<Long, Category> rankCategories(Map<Long, BigDecimal> unitsByKiosk) {
        List<Map.Entry<Long, BigDecimal>> sorted = new ArrayList<>(unitsByKiosk.entrySet());
        sorted.sort((a, b) -> nz(b.getValue()).compareTo(nz(a.getValue())));
        Map<Long, Category> out = new LinkedHashMap<>();
        int n = sorted.size();
        for (int i = 0; i < n; i++) {
            Category c = i < Math.ceil(n / 3.0) ? Category.A
                    : i < Math.ceil(2 * n / 3.0) ? Category.B
                    : Category.C;
            out.put(sorted.get(i).getKey(), c);
        }
        return out;
    }

    /**
     * Unidades por envío de un kiosco: su venta de {@link #REVIEW_DAYS} días por el factor de la categoría
     * (A 100%, B 80%, C 60%). Con datos de prod (nov-2026) los kioscos reciben envíos chicos y frecuentes
     * (5–9 unidades) que suman ~80% de lo que venden; sin tope, la primera sugerencia pedía todos los huecos de
     * golpe (hasta 137 unidades a un solo kiosco).
     */
    public static int weeklyBudget(Category category, BigDecimal unitsSold, int windowDays) {
        if (windowDays <= 0 || unitsSold == null || unitsSold.signum() <= 0) {
            return 0;
        }
        BigDecimal weekly = unitsSold.multiply(BigDecimal.valueOf(REVIEW_DAYS))
                .multiply(BigDecimal.valueOf(category.budgetFactor))
                .divide(BigDecimal.valueOf(windowDays), 4, RoundingMode.HALF_UP);
        return ceil(weekly);
    }

    /**
     * Recorta lo que pide cada línea de un mismo kiosco para no pasar de {@code budget}: primero artículos base y
     * luego las que más venden. Lo que no entra queda para el siguiente envío.
     */
    public static int[] applyBudget(List<AllocationRequest> kioskRequests, int budget) {
        int[] capped = new int[kioskRequests.size()];
        int left = Math.max(0, budget);
        for (int i : priorityOrder(kioskRequests)) {
            int give = Math.min(Math.max(0, kioskRequests.get(i).need()), left);
            capped[i] = give;
            left -= give;
        }
        return capped;
    }

    /** Lo que pide una línea de un kiosco contra el stock de bodega de {@code stockKey}. */
    public record AllocationRequest(String stockKey, Category category, boolean base, BigDecimal dailyRate, int need) {
    }

    /**
     * Reparte el disponible de bodega entre las líneas: primero A, luego B y C; dentro de la misma categoría
     * primero artículos base y luego la línea que más vende. Devuelve lo asignado a cada request (mismo índice).
     */
    public static int[] allocate(List<AllocationRequest> requests, Map<String, Integer> availableByKey) {
        int[] allocated = new int[requests.size()];
        Map<String, Integer> remaining = new HashMap<>(availableByKey);
        for (int i : priorityOrder(requests)) {
            AllocationRequest r = requests.get(i);
            int left = Math.max(0, remaining.getOrDefault(r.stockKey(), 0));
            int give = Math.min(Math.max(0, r.need()), left);
            allocated[i] = give;
            remaining.put(r.stockKey(), left - give);
        }
        return allocated;
    }

    private static List<Integer> priorityOrder(List<AllocationRequest> requests) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator
                .comparing((Integer i) -> requests.get(i).category().ordinal())
                .thenComparing(i -> requests.get(i).base() ? 0 : 1)
                .thenComparing(i -> nz(requests.get(i).dailyRate()), Comparator.reverseOrder()));
        return order;
    }

    private static int ceil(BigDecimal v) {
        return v.setScale(0, RoundingMode.CEILING).intValue();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
