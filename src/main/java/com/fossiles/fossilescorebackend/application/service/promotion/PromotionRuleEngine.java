package com.fossiles.fossilescorebackend.application.service.promotion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Motor de promociones dinámicas (discount_type = RULES). Sin Spring ni base de datos:
 * recibe la regla, las líneas del carrito y el contexto (fecha, hora, pago) y devuelve
 * el descuento de cada línea con su explicación. Lo usan la venta, el estimado del POS y el simulador.
 *
 * Proceso: condiciones → filtro de piezas → mínimos → acción → topes.
 */
public final class PromotionRuleEngine {

    public static final String TYPE_PERCENT = "PERCENT";
    public static final String TYPE_AMOUNT_EACH = "AMOUNT_EACH";
    public static final String TYPE_FIXED_PRICE = "FIXED_PRICE";
    public static final String TYPE_POSITION = "POSITION";
    public static final String TYPE_QTY_TIERS = "QTY_TIERS";
    public static final String TYPE_AMOUNT_TIERS = "AMOUNT_TIERS";
    public static final String TYPE_BUY_GET = "BUY_GET";

    private static final Set<String> TYPES = Set.of(
            TYPE_PERCENT, TYPE_AMOUNT_EACH, TYPE_FIXED_PRICE, TYPE_POSITION,
            TYPE_QTY_TIERS, TYPE_AMOUNT_TIERS, TYPE_BUY_GET);
    private static final Set<String> PAYMENT_METHODS = Set.of("EFECTIVO", "TARJETA", "TRANSFERENCIA", "MIXTO");
    private static final String[] DAY_NAMES = {"", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"};
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Evita expandir cantidades absurdas en piezas. */
    private static final int MAX_UNITS_PER_LINE = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PromotionRuleEngine() {
    }

    /**
     * Línea del carrito.
     *
     * @param unitPrice precio base por pieza (sin complemento extra)
     * @param eligible  false para empaque o precio editado final: nunca recibe descuento
     */
    public record Line(
            int index,
            Long productId,
            Long categoryId,
            String audience,
            Long colorId,
            String size,
            BigDecimal quantity,
            BigDecimal unitPrice,
            boolean eligible
    ) {
        BigDecimal baseTotal() {
            return safe(unitPrice).multiply(safe(quantity)).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** @param paymentMethod null = todavía no se eligió (estimado del carrito). */
    public record Context(LocalDate date, LocalTime time, String paymentMethod) {
    }

    public record Result(
            boolean applies,
            BigDecimal discount,
            List<BigDecimal> lineDiscounts,
            List<String> lineNotes,
            List<String> reasons
    ) {
        static Result none(int lineCount, List<String> reasons) {
            List<BigDecimal> zeros = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (int i = 0; i < lineCount; i++) {
                zeros.add(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                notes.add(null);
            }
            return new Result(false, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), zeros, notes, reasons);
        }
    }

    private record Unit(int line, BigDecimal price, int seq) {
    }

    // ---------------------------------------------------------------- JSON

    public static String toJson(PromotionRuleConfig config) {
        if (config == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar la regla de la promoción.", e);
        }
    }

    public static PromotionRuleConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, PromotionRuleConfig.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- evaluación

    public static Result evaluate(PromotionRuleConfig config, List<Line> lines, Context context) {
        List<Line> safeLines = lines == null ? List.of() : lines;
        int n = safeLines.size();
        if (config == null || config.getAction() == null || config.getAction().getType() == null) {
            return Result.none(n, List.of("La promoción no tiene una regla configurada."));
        }
        PromotionRuleConfig.Conditions conditions = config.getConditions() != null
                ? config.getConditions()
                : new PromotionRuleConfig.Conditions();
        PromotionRuleConfig.Action action = config.getAction();
        PromotionRuleConfig.Limits limits = config.getLimits() != null
                ? config.getLimits()
                : new PromotionRuleConfig.Limits();

        List<String> reasons = checkConditions(conditions, context);
        if (!reasons.isEmpty()) {
            return Result.none(n, reasons);
        }

        List<Line> eligible = safeLines.stream()
                .filter(Line::eligible)
                .filter(line -> matches(config.getFilter(), line))
                .filter(line -> safe(line.quantity()).signum() > 0)
                .toList();
        if (eligible.isEmpty()) {
            return Result.none(n, List.of("Ninguna pieza del carrito entra en la promoción."));
        }

        BigDecimal eligibleQty = eligible.stream().map(l -> safe(l.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal eligibleAmount = eligible.stream().map(Line::baseTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (conditions.getMinQty() != null && conditions.getMinQty() > 0
                && eligibleQty.compareTo(BigDecimal.valueOf(conditions.getMinQty())) < 0) {
            return Result.none(n, List.of("Necesita al menos " + conditions.getMinQty()
                    + " piezas que entren en la promoción (hay " + formatQty(eligibleQty) + ")."));
        }
        if (conditions.getMinAmount() != null && conditions.getMinAmount().signum() > 0
                && eligibleAmount.compareTo(conditions.getMinAmount()) < 0) {
            return Result.none(n, List.of("Necesita compras de al menos Q" + money(conditions.getMinAmount())
                    + " en piezas de la promoción (hay Q" + money(eligibleAmount) + ")."));
        }

        BigDecimal[] discounts = new BigDecimal[n];
        List<Map<String, Integer>> notes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            discounts[i] = BigDecimal.ZERO;
            notes.add(new LinkedHashMap<>());
        }
        Map<Integer, Integer> positionByIndex = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            positionByIndex.put(safeLines.get(i).index(), i);
        }

        String type = normalize(action.getType());
        switch (type) {
            case TYPE_PERCENT -> {
                BigDecimal pct = clampPercent(action.getValue());
                for (Line line : eligible) {
                    add(discounts, notes, positionByIndex.get(line.index()),
                            percentOf(line.baseTotal(), pct), pct(pct));
                }
            }
            case TYPE_AMOUNT_EACH -> {
                BigDecimal value = safe(action.getValue());
                for (Line line : eligible) {
                    BigDecimal perUnit = value.min(safe(line.unitPrice())).max(BigDecimal.ZERO);
                    add(discounts, notes, positionByIndex.get(line.index()),
                            perUnit.multiply(safe(line.quantity())).setScale(2, RoundingMode.HALF_UP),
                            "−Q" + money(value) + " c/u");
                }
            }
            case TYPE_FIXED_PRICE -> {
                BigDecimal target = safe(action.getValue());
                for (Line line : eligible) {
                    BigDecimal perUnit = safe(line.unitPrice()).subtract(target).max(BigDecimal.ZERO);
                    if (perUnit.signum() > 0) {
                        add(discounts, notes, positionByIndex.get(line.index()),
                                perUnit.multiply(safe(line.quantity())).setScale(2, RoundingMode.HALF_UP),
                                "a Q" + money(target) + " c/u");
                    }
                }
            }
            case TYPE_POSITION -> applyPosition(action, limits, eligible, positionByIndex, discounts, notes);
            case TYPE_QTY_TIERS -> {
                PromotionRuleConfig.QtyTier tier = bestQtyTier(action.getQtyTiers(), eligibleQty);
                if (tier == null) {
                    return Result.none(n, List.of("Con " + formatQty(eligibleQty)
                            + " piezas no se alcanza ningún escalón."));
                }
                BigDecimal pct = clampPercent(tier.getPercent());
                for (Line line : eligible) {
                    add(discounts, notes, positionByIndex.get(line.index()),
                            percentOf(line.baseTotal(), pct), tier.getMinQty() + "+ piezas · " + pct(pct));
                }
            }
            case TYPE_AMOUNT_TIERS -> {
                PromotionRuleConfig.AmountTier tier = bestAmountTier(action.getAmountTiers(), eligibleAmount);
                if (tier == null) {
                    return Result.none(n, List.of("Con Q" + money(eligibleAmount)
                            + " no se alcanza ningún escalón de monto."));
                }
                String label = "Compra ≥ Q" + money(tier.getMinAmount());
                if (tier.getPercent() != null && tier.getPercent().signum() > 0) {
                    BigDecimal pct = clampPercent(tier.getPercent());
                    for (Line line : eligible) {
                        add(discounts, notes, positionByIndex.get(line.index()),
                                percentOf(line.baseTotal(), pct), label + " · " + pct(pct));
                    }
                } else {
                    BigDecimal amount = safe(tier.getAmount()).min(eligibleAmount);
                    distribute(amount, eligible, positionByIndex, discounts, notes, label + " · −Q" + money(amount));
                }
            }
            case TYPE_BUY_GET -> applyBuyGet(action, limits, config.getFilter(), safeLines, eligible,
                    positionByIndex, discounts, notes);
            default -> {
                return Result.none(n, List.of("Tipo de descuento desconocido: " + action.getType()));
            }
        }

        // Ninguna línea puede quedar negativa.
        for (int i = 0; i < n; i++) {
            Line line = safeLines.get(i);
            discounts[i] = discounts[i].min(line.baseTotal()).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal total = sum(discounts);
        if (limits.getMaxDiscount() != null && limits.getMaxDiscount().signum() > 0
                && total.compareTo(limits.getMaxDiscount()) > 0) {
            scaleTo(discounts, limits.getMaxDiscount());
            for (int i = 0; i < n; i++) {
                if (discounts[i].signum() > 0) {
                    notes.get(i).merge("tope Q" + money(limits.getMaxDiscount()), 1, (a, b) -> a);
                }
            }
            total = sum(discounts);
        }

        List<BigDecimal> lineDiscounts = new ArrayList<>();
        List<String> lineNotes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lineDiscounts.add(discounts[i]);
            lineNotes.add(discounts[i].signum() > 0 ? joinNotes(notes.get(i)) : null);
        }
        if (total.signum() <= 0) {
            return new Result(false, total, lineDiscounts, lineNotes,
                    List.of("La regla no genera descuento con este carrito."));
        }
        return new Result(true, total, lineDiscounts, lineNotes, List.of());
    }

    private static void applyPosition(
            PromotionRuleConfig.Action action,
            PromotionRuleConfig.Limits limits,
            List<Line> eligible,
            Map<Integer, Integer> positionByIndex,
            BigDecimal[] discounts,
            List<Map<String, Integer>> notes
    ) {
        List<Unit> units = sortUnits(expandUnits(eligible), action.getOrder(), true);
        if (units.isEmpty()) {
            return;
        }
        List<BigDecimal> positions = action.getPositions() != null ? action.getPositions() : List.of();
        int groupSize = action.getGroupSize() != null && action.getGroupSize() > 0
                ? action.getGroupSize()
                : units.size();
        boolean incompleteAsRemainder = "REMAINDER".equals(normalize(action.getIncompleteGroup()));
        BigDecimal remainder = action.getRemainderPercent() != null ? clampPercent(action.getRemainderPercent()) : BigDecimal.ZERO;
        Integer maxApps = limits.getMaxApplications() != null && limits.getMaxApplications() > 0
                ? limits.getMaxApplications()
                : null;

        int group = 0;
        for (int start = 0; start < units.size(); start += groupSize) {
            int end = Math.min(start + groupSize, units.size());
            boolean complete = end - start == groupSize;
            group++;
            if (maxApps != null && group > maxApps) {
                break;
            }
            for (int i = start; i < end; i++) {
                int pos = i - start;
                BigDecimal pct;
                String label;
                if (!complete && incompleteAsRemainder) {
                    pct = remainder;
                    label = "Sobrante · " + pct(pct);
                } else if (pos < positions.size()) {
                    pct = clampPercent(positions.get(pos));
                    label = "Pos. " + (pos + 1) + " · " + pct(pct);
                } else {
                    pct = remainder;
                    label = "Resto · " + pct(pct);
                }
                if (pct.signum() <= 0) {
                    continue;
                }
                Unit unit = units.get(i);
                add(discounts, notes, positionByIndex.get(unit.line()), percentOf(unit.price(), pct), label);
            }
        }
    }

    private static void applyBuyGet(
            PromotionRuleConfig.Action action,
            PromotionRuleConfig.Limits limits,
            PromotionRuleConfig.Filter mainFilter,
            List<Line> allLines,
            List<Line> eligible,
            Map<Integer, Integer> positionByIndex,
            BigDecimal[] discounts,
            List<Map<String, Integer>> notes
    ) {
        int buyQty = action.getBuyQty() != null ? action.getBuyQty() : 0;
        int getQty = action.getGetQty() != null ? action.getGetQty() : 0;
        BigDecimal pct = clampPercent(action.getGetPercent());
        if (buyQty <= 0 || getQty <= 0 || pct.signum() <= 0) {
            return;
        }
        PromotionRuleConfig.Filter buyFilter = action.getBuyFilter() != null ? action.getBuyFilter() : mainFilter;
        List<Line> buyLines = allLines.stream()
                .filter(Line::eligible)
                .filter(line -> matches(buyFilter, line))
                .toList();
        // Disparadores: los más caros primero; los que reciben descuento según el orden (por defecto el más barato).
        List<Unit> buyUnits = sortUnits(expandUnits(buyLines), "DESC", true);
        List<Unit> getUnits = sortUnits(expandUnits(eligible), action.getOrder(), false);
        Set<String> used = new HashSet<>();
        Integer maxApps = limits.getMaxApplications() != null && limits.getMaxApplications() > 0
                ? limits.getMaxApplications()
                : null;
        int applications = 0;
        while (maxApps == null || applications < maxApps) {
            List<Unit> triggers = new ArrayList<>();
            for (Unit unit : buyUnits) {
                if (triggers.size() == buyQty) {
                    break;
                }
                if (!used.contains(key(unit))) {
                    triggers.add(unit);
                }
            }
            if (triggers.size() < buyQty) {
                break;
            }
            Set<String> triggerKeys = triggers.stream().map(PromotionRuleEngine::key).collect(Collectors.toSet());
            List<Unit> rewarded = new ArrayList<>();
            for (Unit unit : getUnits) {
                if (rewarded.size() == getQty) {
                    break;
                }
                String k = key(unit);
                if (!used.contains(k) && !triggerKeys.contains(k)) {
                    rewarded.add(unit);
                }
            }
            if (rewarded.isEmpty()) {
                break;
            }
            used.addAll(triggerKeys);
            for (Unit unit : rewarded) {
                used.add(key(unit));
                add(discounts, notes, positionByIndex.get(unit.line()), percentOf(unit.price(), pct),
                        "Compra " + buyQty + " lleva · " + pct(pct));
            }
            applications++;
        }
    }

    // ---------------------------------------------------------------- condiciones y filtro

    static List<String> checkConditions(PromotionRuleConfig.Conditions conditions, Context context) {
        List<String> reasons = new ArrayList<>();
        if (conditions == null) {
            return reasons;
        }
        LocalDate date = context != null ? context.date() : null;
        LocalTime time = context != null ? context.time() : null;
        List<Integer> days = conditions.getDaysOfWeek();
        if (days != null && !days.isEmpty() && date != null && !days.contains(date.getDayOfWeek().getValue())) {
            reasons.add("Solo aplica los días: " + days.stream()
                    .filter(d -> d != null && d >= 1 && d <= 7)
                    .sorted()
                    .map(d -> DAY_NAMES[d])
                    .collect(Collectors.joining(", ")) + ".");
        }
        LocalTime from = parseTime(conditions.getTimeFrom());
        LocalTime to = parseTime(conditions.getTimeTo());
        if ((from != null || to != null) && time != null && !inTimeWindow(time, from, to)) {
            reasons.add("Solo aplica " + describeWindow(from, to) + ".");
        }
        List<String> methods = conditions.getPaymentMethods() == null ? List.of() : conditions.getPaymentMethods().stream()
                .map(PromotionRuleEngine::normalize)
                .filter(m -> !m.isBlank())
                .toList();
        if (!methods.isEmpty()) {
            String payment = context != null ? normalize(context.paymentMethod()) : "";
            if (payment.isBlank()) {
                reasons.add("Solo pagando con " + humanPayments(methods) + " (falta elegir el método de pago).");
            } else if (!methods.contains(payment)) {
                reasons.add("Solo pagando con " + humanPayments(methods) + ".");
            }
        }
        return reasons;
    }

    public static boolean matches(PromotionRuleConfig.Filter filter, Line line) {
        if (filter == null) {
            return true;
        }
        if (notEmpty(filter.getAudiences())) {
            String audience = ProductAudienceCategory.normalizeProductAudience(line.audience());
            Set<String> allowed = filter.getAudiences().stream().map(PromotionRuleEngine::normalize).collect(Collectors.toSet());
            if (!allowed.contains(audience)) {
                return false;
            }
        }
        if (notEmpty(filter.getCategoryIds()) && !filter.getCategoryIds().contains(line.categoryId())) {
            return false;
        }
        if (notEmpty(filter.getProductIds()) && !filter.getProductIds().contains(line.productId())) {
            return false;
        }
        if (notEmpty(filter.getColorIds()) && !filter.getColorIds().contains(line.colorId())) {
            return false;
        }
        if (notEmpty(filter.getSizes())) {
            String size = normalize(line.size());
            boolean any = filter.getSizes().stream().map(PromotionRuleEngine::normalize).anyMatch(size::equals);
            if (!any) {
                return false;
            }
        }
        BigDecimal price = safe(line.unitPrice());
        if (filter.getMinUnitPrice() != null && price.compareTo(filter.getMinUnitPrice()) < 0) {
            return false;
        }
        if (filter.getMaxUnitPrice() != null && filter.getMaxUnitPrice().signum() > 0
                && price.compareTo(filter.getMaxUnitPrice()) > 0) {
            return false;
        }
        if (notEmpty(filter.getExcludeCategoryIds()) && filter.getExcludeCategoryIds().contains(line.categoryId())) {
            return false;
        }
        return !notEmpty(filter.getExcludeProductIds()) || !filter.getExcludeProductIds().contains(line.productId());
    }

    // ---------------------------------------------------------------- validación

    /** Errores en español listos para mostrar; vacío = la regla es válida. */
    public static List<String> validate(PromotionRuleConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null || config.getAction() == null) {
            errors.add("Debes configurar la regla de la promoción.");
            return errors;
        }
        PromotionRuleConfig.Action action = config.getAction();
        String type = normalize(action.getType());
        if (!TYPES.contains(type)) {
            errors.add("Elige qué descuento hace la promoción.");
            return errors;
        }
        PromotionRuleConfig.Conditions c = config.getConditions();
        if (c != null) {
            if (c.getDaysOfWeek() != null && c.getDaysOfWeek().stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
                errors.add("Los días de la semana van del 1 (lunes) al 7 (domingo).");
            }
            if (notBlank(c.getTimeFrom()) && parseTime(c.getTimeFrom()) == null) {
                errors.add("Hora de inicio inválida (usa HH:mm).");
            }
            if (notBlank(c.getTimeTo()) && parseTime(c.getTimeTo()) == null) {
                errors.add("Hora de fin inválida (usa HH:mm).");
            }
            if (c.getPaymentMethods() != null && c.getPaymentMethods().stream()
                    .map(PromotionRuleEngine::normalize)
                    .anyMatch(m -> !PAYMENT_METHODS.contains(m))) {
                errors.add("Método de pago inválido.");
            }
            if (c.getMinQty() != null && c.getMinQty() < 0) {
                errors.add("El mínimo de piezas no puede ser negativo.");
            }
            if (c.getMinAmount() != null && c.getMinAmount().signum() < 0) {
                errors.add("El monto mínimo no puede ser negativo.");
            }
        }
        PromotionRuleConfig.Filter f = config.getFilter();
        if (f != null && f.getMinUnitPrice() != null && f.getMaxUnitPrice() != null
                && f.getMaxUnitPrice().signum() > 0 && f.getMaxUnitPrice().compareTo(f.getMinUnitPrice()) < 0) {
            errors.add("El precio máximo no puede ser menor al mínimo.");
        }
        switch (type) {
            case TYPE_PERCENT -> requirePercent(errors, action.getValue(), "El porcentaje");
            case TYPE_AMOUNT_EACH -> requirePositive(errors, action.getValue(), "El monto a descontar por pieza");
            case TYPE_FIXED_PRICE -> {
                if (action.getValue() == null || action.getValue().signum() < 0) {
                    errors.add("Indica el precio fijo por pieza.");
                }
            }
            case TYPE_POSITION -> {
                if (action.getGroupSize() != null && (action.getGroupSize() < 0 || action.getGroupSize() > 50)) {
                    errors.add("El tamaño del grupo debe estar entre 1 y 50 (0 = todas las piezas).");
                }
                List<BigDecimal> positions = action.getPositions();
                if (positions == null || positions.isEmpty()) {
                    errors.add("Indica el porcentaje de al menos una posición.");
                } else {
                    if (action.getGroupSize() != null && action.getGroupSize() > 0
                            && positions.size() > action.getGroupSize()) {
                        errors.add("Hay más posiciones que piezas por grupo.");
                    }
                    for (BigDecimal p : positions) {
                        if (p == null || p.signum() < 0 || p.compareTo(HUNDRED) > 0) {
                            errors.add("Cada posición debe tener un porcentaje entre 0 y 100.");
                            break;
                        }
                    }
                    boolean anyPositive = positions.stream().anyMatch(p -> p != null && p.signum() > 0)
                            || (action.getRemainderPercent() != null && action.getRemainderPercent().signum() > 0);
                    if (!anyPositive) {
                        errors.add("Al menos una posición debe tener descuento.");
                    }
                }
                if (action.getRemainderPercent() != null
                        && (action.getRemainderPercent().signum() < 0 || action.getRemainderPercent().compareTo(HUNDRED) > 0)) {
                    errors.add("El porcentaje del resto debe estar entre 0 y 100.");
                }
            }
            case TYPE_QTY_TIERS -> {
                List<PromotionRuleConfig.QtyTier> tiers = action.getQtyTiers();
                if (tiers == null || tiers.isEmpty()) {
                    errors.add("Agrega al menos un escalón por cantidad.");
                } else {
                    Set<Integer> seen = new HashSet<>();
                    for (PromotionRuleConfig.QtyTier t : tiers) {
                        if (t == null || t.getMinQty() == null || t.getMinQty() < 1) {
                            errors.add("Cada escalón necesita una cantidad mínima de 1 o más.");
                            break;
                        }
                        if (!seen.add(t.getMinQty())) {
                            errors.add("Hay dos escalones con la misma cantidad.");
                            break;
                        }
                        requirePercent(errors, t.getPercent(), "El porcentaje del escalón de " + t.getMinQty() + " piezas");
                    }
                }
            }
            case TYPE_AMOUNT_TIERS -> {
                List<PromotionRuleConfig.AmountTier> tiers = action.getAmountTiers();
                if (tiers == null || tiers.isEmpty()) {
                    errors.add("Agrega al menos un escalón por monto.");
                } else {
                    for (PromotionRuleConfig.AmountTier t : tiers) {
                        if (t == null || t.getMinAmount() == null || t.getMinAmount().signum() <= 0) {
                            errors.add("Cada escalón necesita un monto mínimo mayor a cero.");
                            break;
                        }
                        boolean hasPct = t.getPercent() != null && t.getPercent().signum() > 0;
                        boolean hasAmount = t.getAmount() != null && t.getAmount().signum() > 0;
                        if (hasPct == hasAmount) {
                            errors.add("Cada escalón por monto lleva un porcentaje o un monto en Q (uno de los dos).");
                            break;
                        }
                        if (hasPct) {
                            requirePercent(errors, t.getPercent(), "El porcentaje del escalón");
                        }
                    }
                }
            }
            case TYPE_BUY_GET -> {
                if (action.getBuyQty() == null || action.getBuyQty() < 1) {
                    errors.add("Indica cuántas piezas hay que comprar.");
                }
                if (action.getGetQty() == null || action.getGetQty() < 1) {
                    errors.add("Indica cuántas piezas llevan el descuento.");
                }
                requirePercent(errors, action.getGetPercent(), "El porcentaje de las piezas que llevan descuento");
            }
            default -> {
            }
        }
        PromotionRuleConfig.Limits limits = config.getLimits();
        if (limits != null) {
            if (limits.getMaxDiscount() != null && limits.getMaxDiscount().signum() < 0) {
                errors.add("El tope de descuento no puede ser negativo.");
            }
            if (limits.getMaxApplications() != null && limits.getMaxApplications() < 0) {
                errors.add("El máximo de veces por venta no puede ser negativo.");
            }
        }
        return errors;
    }

    // ---------------------------------------------------------------- utilidades

    private static List<Unit> expandUnits(List<Line> lines) {
        List<Unit> units = new ArrayList<>();
        int seq = 0;
        for (Line line : lines) {
            int whole = Math.min(MAX_UNITS_PER_LINE,
                    safe(line.quantity()).setScale(0, RoundingMode.DOWN).intValue());
            for (int i = 0; i < whole; i++) {
                units.add(new Unit(line.index(), safe(line.unitPrice()), seq++));
            }
        }
        return units;
    }

    private static List<Unit> sortUnits(List<Unit> units, String order, boolean defaultDesc) {
        String normalized = normalize(order);
        boolean desc = normalized.isBlank() ? defaultDesc : !"ASC".equals(normalized);
        Comparator<Unit> byPrice = Comparator.comparing(Unit::price);
        if (desc) {
            byPrice = byPrice.reversed();
        }
        List<Unit> sorted = new ArrayList<>(units);
        sorted.sort(byPrice.thenComparingInt(Unit::seq));
        return sorted;
    }

    private static String key(Unit unit) {
        return unit.line() + ":" + unit.seq();
    }

    private static PromotionRuleConfig.QtyTier bestQtyTier(List<PromotionRuleConfig.QtyTier> tiers, BigDecimal qty) {
        if (tiers == null) {
            return null;
        }
        return tiers.stream()
                .filter(Objects::nonNull)
                .filter(t -> t.getMinQty() != null && qty.compareTo(BigDecimal.valueOf(t.getMinQty())) >= 0)
                .max(Comparator.comparing(PromotionRuleConfig.QtyTier::getMinQty))
                .orElse(null);
    }

    private static PromotionRuleConfig.AmountTier bestAmountTier(List<PromotionRuleConfig.AmountTier> tiers, BigDecimal amount) {
        if (tiers == null) {
            return null;
        }
        return tiers.stream()
                .filter(Objects::nonNull)
                .filter(t -> t.getMinAmount() != null && amount.compareTo(t.getMinAmount()) >= 0)
                .max(Comparator.comparing(PromotionRuleConfig.AmountTier::getMinAmount))
                .orElse(null);
    }

    /** Reparte un monto fijo entre las líneas en proporción a su total; el centavo de diferencia va a la más grande. */
    private static void distribute(
            BigDecimal amount,
            List<Line> lines,
            Map<Integer, Integer> positionByIndex,
            BigDecimal[] discounts,
            List<Map<String, Integer>> notes,
            String label
    ) {
        BigDecimal base = lines.stream().map(Line::baseTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.signum() <= 0 || base.signum() <= 0) {
            return;
        }
        BigDecimal assigned = BigDecimal.ZERO;
        Line largest = null;
        for (Line line : lines) {
            BigDecimal share = amount.multiply(line.baseTotal()).divide(base, 2, RoundingMode.HALF_UP);
            assigned = assigned.add(share);
            add(discounts, notes, positionByIndex.get(line.index()), share, label);
            if (largest == null || line.baseTotal().compareTo(largest.baseTotal()) > 0) {
                largest = line;
            }
        }
        BigDecimal residue = amount.subtract(assigned);
        if (residue.signum() != 0 && largest != null) {
            int pos = positionByIndex.get(largest.index());
            discounts[pos] = discounts[pos].add(residue);
        }
    }

    private static void scaleTo(BigDecimal[] discounts, BigDecimal cap) {
        BigDecimal total = sum(discounts);
        if (total.signum() <= 0) {
            return;
        }
        BigDecimal assigned = BigDecimal.ZERO;
        int largest = -1;
        for (int i = 0; i < discounts.length; i++) {
            BigDecimal scaled = discounts[i].multiply(cap).divide(total, 2, RoundingMode.DOWN);
            if (largest < 0 || discounts[i].compareTo(discounts[largest]) > 0) {
                largest = i;
            }
            discounts[i] = scaled;
            assigned = assigned.add(scaled);
        }
        if (largest >= 0) {
            discounts[largest] = discounts[largest].add(cap.subtract(assigned));
        }
    }

    private static void add(BigDecimal[] discounts, List<Map<String, Integer>> notes, Integer pos, BigDecimal amount, String label) {
        if (pos == null || amount == null || amount.signum() <= 0) {
            return;
        }
        discounts[pos] = discounts[pos].add(amount);
        notes.get(pos).merge(label, 1, Integer::sum);
    }

    private static String joinNotes(Map<String, Integer> notes) {
        String text = notes.entrySet().stream()
                .map(e -> e.getValue() > 1 ? e.getValue() + "× " + e.getKey() : e.getKey())
                .collect(Collectors.joining(" + "));
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal pct) {
        return safe(amount).multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal clampPercent(BigDecimal value) {
        return safe(value).max(BigDecimal.ZERO).min(HUNDRED);
    }

    private static BigDecimal sum(BigDecimal[] values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    static boolean inTimeWindow(LocalTime time, LocalTime from, LocalTime to) {
        if (from != null && to != null) {
            if (!to.isBefore(from)) {
                return !time.isBefore(from) && time.isBefore(to);
            }
            return !time.isBefore(from) || time.isBefore(to);
        }
        if (from != null) {
            return !time.isBefore(from);
        }
        return to == null || time.isBefore(to);
    }

    private static String describeWindow(LocalTime from, LocalTime to) {
        if (from != null && to != null) {
            return "de " + from + " a " + to;
        }
        return from != null ? "desde las " + from : "hasta las " + to;
    }

    private static LocalTime parseTime(String value) {
        if (!notBlank(value)) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String humanPayments(List<String> methods) {
        return methods.stream()
                .map(m -> m.charAt(0) + m.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" o "));
    }

    private static void requirePercent(List<String> errors, BigDecimal value, String label) {
        if (value == null || value.signum() <= 0 || value.compareTo(HUNDRED) > 0) {
            errors.add(label + " debe estar entre 0 y 100 (mayor a cero).");
        }
    }

    private static void requirePositive(List<String> errors, BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            errors.add(label + " debe ser mayor a cero.");
        }
    }

    private static String pct(BigDecimal pct) {
        return pct.stripTrailingZeros().toPlainString() + "%";
    }

    private static String money(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatQty(BigDecimal qty) {
        return safe(qty).stripTrailingZeros().toPlainString();
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toUpperCase(Locale.ROOT);
    }
}
