package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.CinchoProductionResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionCinchoDayStatusEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupMemberEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionCinchoDayStatusRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupMemberRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lecturas de las pantallas de cinchos: el cuadro del día del Tablero y «Cinchos en producción».
 *
 * <p>Todo en lote: las órdenes con una consulta, sus productos, colores, consolidadas y estados
 * con una cada uno. Las líneas se eligen con la misma regla que enruta producción
 * ({@link CinchoProductUtils#isMesaCinchosLineForProduction}): en una OP de cinchos van todas;
 * en una venta (OPL/OPCK), solo los cinchos y pulseras.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CinchoProductionService {

    static final List<String> OPC_TYPES = List.of("CINCHOS", "CINCHOS_FOSSILES", "CINCHOS_MARCAS");
    static final List<String> SALE_TYPES = List.of("VENTA_EN_LINEA", "CLIENTE_KIOSKO");
    /** Mismos estados que el cuadro del Tablero tomaba del listado completo. */
    static final List<String> DAY_STATUSES = List.of("PENDING", "IN_PROGRESS", "DRAFT");
    static final List<String> ACTIVE_STATUSES = List.of("PENDING", "IN_PROGRESS", "IN_QA", "DRAFT");
    static final int CLOSED_LIMIT = 150;

    private static final List<String> SIZE_ORDER = List.of("XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL");

    private final ProductionOrderRepository orderRepository;
    private final ProductionOrderItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductionOrderGroupMemberRepository groupMemberRepository;
    private final ProductionOrderGroupRepository groupRepository;
    private final ProductionCinchoDayStatusRepository statusRepository;
    private final ObjectMapper objectMapper;

    // ==================== CUADRO DEL DÍA ====================

    public CinchoProductionResponse.Day day(LocalDate date) throws BusinessException {
        if (date == null) {
            throw new BusinessException("La fecha es obligatoria.");
        }
        List<ProductionOrderEntity> orders = orderRepository.findCinchoOrdersForDay(allTypes(), DAY_STATUSES, date);
        List<Long> ids = orders.stream().map(ProductionOrderEntity::getId).toList();
        Map<Long, ProductionCinchoDayStatusEntity> statusByItem = ids.isEmpty() ? Map.of()
                : statusRepository.findByWorkDateAndProductionOrderIdIn(date, ids).stream()
                .collect(Collectors.toMap(ProductionCinchoDayStatusEntity::getProductionOrderItemId,
                        Function.identity(), (a, b) -> b));
        List<CinchoProductionResponse.Order> out = build(orders, item -> {
            ProductionCinchoDayStatusEntity s = statusByItem.get(item.getId());
            return s == null ? new LineState(CinchoProductUtils.WORK_STATUS_PENDING, false, null)
                    : new LineState(workStatusOf(s), Boolean.TRUE.equals(s.getDelivered()), s.getWorkDate());
        });
        if (ProductionPlanningConstants.isWorkday(date) && !date.isBefore(GuatemalaDateTime.today())) {
            out.addAll(carriedOver(date));
        }
        return CinchoProductionResponse.Day.builder().date(date).orders(out).build();
    }

    /**
     * Lo que quedó sin terminar del día hábil anterior, como en las mesas: las líneas de las OP
     * activas de ese día que no quedaron completadas y entregadas. Solo de ese día, no de más
     * atrás (las viejas confunden; siguen en «Cinchos en producción»). Su estado es lo último que
     * se marcó en ella hasta hoy. Solo para días hábiles de hoy en adelante.
     */
    private List<CinchoProductionResponse.Order> carriedOver(LocalDate date) {
        List<ProductionOrderEntity> before = orderRepository.findCinchoOrdersForDay(
                allTypes(), DAY_STATUSES, ProductionPlanningConstants.previousWorkday(date));
        if (before.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = itemRepository.findByProductionOrderIdIn(before.stream().map(ProductionOrderEntity::getId).toList())
                .stream().map(ProductionOrderItemEntity::getId).toList();
        Map<Long, List<ProductionCinchoDayStatusEntity>> byItem = itemIds.isEmpty() ? Map.of()
                : statusRepository.findByProductionOrderItemIdIn(itemIds).stream()
                .filter(s -> !s.getWorkDate().isAfter(date))
                .collect(Collectors.groupingBy(ProductionCinchoDayStatusEntity::getProductionOrderItemId));
        List<CinchoProductionResponse.Order> carried = build(before, item -> {
            List<ProductionCinchoDayStatusEntity> rows = byItem.getOrDefault(item.getId(), List.of());
            if (rows.isEmpty()) {
                return new LineState(CinchoProductUtils.WORK_STATUS_PENDING, false, null);
            }
            ProductionCinchoDayStatusEntity last = rows.stream()
                    .max(Comparator.comparing(ProductionCinchoDayStatusEntity::getWorkDate)
                            .thenComparing(ProductionCinchoDayStatusEntity::getId))
                    .orElseThrow();
            boolean completed = rows.stream()
                    .anyMatch(r -> CinchoProductUtils.WORK_STATUS_COMPLETED.equals(workStatusOf(r)));
            boolean delivered = rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.getDelivered()));
            return new LineState(completed ? CinchoProductUtils.WORK_STATUS_COMPLETED : workStatusOf(last),
                    delivered, last.getWorkDate());
        });
        Map<Long, LocalDate> anchorById = before.stream().collect(Collectors.toMap(ProductionOrderEntity::getId,
                o -> o.getStartDate() != null ? o.getStartDate() : o.getDeliveryDate(), (a, b) -> a));
        List<CinchoProductionResponse.Order> out = new ArrayList<>();
        for (CinchoProductionResponse.Order o : carried) {
            // Terminada = completada y entregada, la misma regla con que el cuadro la quita.
            List<CinchoProductionResponse.Line> open = o.getLines().stream()
                    .filter(l -> !(l.isDelivered() && CinchoProductUtils.WORK_STATUS_COMPLETED.equals(l.getWorkStatus())))
                    .collect(Collectors.toList());
            if (open.isEmpty()) {
                continue;
            }
            o.setCarriedFrom(anchorById.get(o.getId()));
            o.setLines(open);
            o.setLineCount(open.size());
            o.setTotalQty(open.stream().mapToInt(CinchoProductionResponse.Line::getTotalQty).sum());
            o.setLinesDone((int) open.stream().filter(CinchoProductionService::isDone).count());
            o.setLinesInProgress((int) open.stream()
                    .filter(l -> !isDone(l) && CinchoProductUtils.WORK_STATUS_IN_PROGRESS.equals(l.getWorkStatus()))
                    .count());
            out.add(o);
        }
        return out;
    }

    // ==================== LISTADO ====================

    /**
     * @param scope  ACTIVE (por omisión) | PENDING | IN_PROGRESS | COMPLETED | CANCELLED
     * @param source ALL (por omisión) | OPC (órdenes de cinchos) | SALES (cinchos de ventas OPL/OPCK)
     * @param search código, cliente, vendedor, consolidada, producto o color; todas las palabras
     */
    public CinchoProductionResponse.Listing list(String scope, String source, String search) throws BusinessException {
        String sc = scope == null || scope.isBlank() ? "ACTIVE" : scope.trim().toUpperCase(Locale.ROOT);
        String src = source == null || source.isBlank() ? "ALL" : source.trim().toUpperCase(Locale.ROOT);
        List<String> types = switch (src) {
            case "ALL" -> allTypes();
            case "OPC" -> OPC_TYPES;
            case "SALES" -> SALE_TYPES;
            default -> throw new BusinessException("Origen inválido: use ALL, OPC o SALES.");
        };

        // Las activas siempre: dan el resumen de arriba, sea cual sea la pestaña.
        List<CinchoProductionResponse.Order> active = build(
                orderRepository.findCinchoOrders(types, ACTIVE_STATUSES), this::noState);
        withProgress(active);

        boolean truncated = false;
        List<CinchoProductionResponse.Order> selected;
        switch (sc) {
            case "ACTIVE" -> selected = active;
            case "PENDING", "IN_PROGRESS" -> selected = active.stream()
                    .filter(o -> sc.equals(upper(o.getStatus()))).collect(Collectors.toList());
            case "COMPLETED", "CANCELLED" -> {
                List<ProductionOrderEntity> recent = orderRepository.findRecentCinchoOrders(
                        types, List.of(sc), PageRequest.of(0, CLOSED_LIMIT + 1));
                truncated = recent.size() > CLOSED_LIMIT;
                selected = build(recent.subList(0, Math.min(recent.size(), CLOSED_LIMIT)), this::noState);
                withProgress(selected);
            }
            default -> throw new BusinessException("Estado inválido: use ACTIVE, PENDING, IN_PROGRESS, COMPLETED o CANCELLED.");
        }

        List<String> words = words(search);
        if (!words.isEmpty()) {
            selected = selected.stream().filter(o -> matches(o, words)).collect(Collectors.toList());
        }
        return CinchoProductionResponse.Listing.builder()
                .scope(sc)
                .source(src)
                .truncated(truncated)
                .summary(summary(active))
                .orders(selected)
                .build();
    }

    // ==================== ARMADO ====================

    private record LineState(String workStatus, boolean delivered, LocalDate lastWorkDate) {}

    private LineState noState(ProductionOrderItemEntity item) {
        return new LineState(CinchoProductUtils.WORK_STATUS_PENDING, false, null);
    }

    private List<CinchoProductionResponse.Order> build(List<ProductionOrderEntity> orders,
                                                       Function<ProductionOrderItemEntity, LineState> stateOf) {
        if (orders.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> orderIds = orders.stream().map(ProductionOrderEntity::getId).toList();
        Map<Long, List<ProductionOrderItemEntity>> itemsByOrder = itemRepository.findByProductionOrderIdIn(orderIds).stream()
                .sorted(Comparator.comparing(ProductionOrderItemEntity::getId))
                .collect(Collectors.groupingBy(ProductionOrderItemEntity::getProductionOrderId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, ProductEntity> products = byId(itemsByOrder.values().stream().flatMap(List::stream)
                .map(ProductionOrderItemEntity::getProductId).filter(Objects::nonNull).collect(Collectors.toSet()),
                productRepository::findAllById, ProductEntity::getId);
        Map<Long, ColorEntity> colors = byId(itemsByOrder.values().stream().flatMap(List::stream)
                .map(ProductionOrderItemEntity::getColorId).filter(Objects::nonNull).collect(Collectors.toSet()),
                colorRepository::findAllById, ColorEntity::getId);
        Map<Long, ProductionOrderGroupEntity> groupByOrder = activeGroups(orderIds);

        List<CinchoProductionResponse.Order> out = new ArrayList<>();
        for (ProductionOrderEntity order : orders) {
            boolean opc = OPC_TYPES.contains(upper(order.getOrderType()));
            List<ProductionOrderItemEntity> items = itemsByOrder.getOrDefault(order.getId(), List.of());
            List<CinchoProductionResponse.Line> lines = new ArrayList<>();
            boolean other = false;
            for (ProductionOrderItemEntity item : items) {
                ProductEntity product = item.getProductId() == null ? null : products.get(item.getProductId());
                if (!opc && !CinchoProductUtils.isMesaCinchosLineForProduction(product)) {
                    other = true;
                    continue;
                }
                lines.add(line(item, product, colors.get(item.getColorId()), stateOf.apply(item)));
            }
            if (lines.isEmpty()) {
                continue;
            }
            ProductionOrderGroupEntity group = groupByOrder.get(order.getId());
            out.add(CinchoProductionResponse.Order.builder()
                    .id(order.getId())
                    .code(order.getCode())
                    .family(familyOf(order.getOrderType()))
                    .orderType(order.getOrderType())
                    .status(order.getStatus())
                    .customerName(order.getCustomerName())
                    .sellerName(order.getSellerName())
                    .startDate(order.getStartDate())
                    .deliveryDate(order.getDeliveryDate())
                    .observations(cleanObservations(order.getObservations()))
                    .mixedOrder(other)
                    .groupId(group == null ? null : group.getId())
                    .groupCode(group == null ? null : group.getCode())
                    .totalQty(lines.stream().mapToInt(CinchoProductionResponse.Line::getTotalQty).sum())
                    .lineCount(lines.size())
                    .linesDone((int) lines.stream().filter(CinchoProductionService::isDone).count())
                    .linesInProgress((int) lines.stream()
                            .filter(l -> !isDone(l) && CinchoProductUtils.WORK_STATUS_IN_PROGRESS.equals(l.getWorkStatus()))
                            .count())
                    .lines(lines)
                    .build());
        }
        return out;
    }

    private CinchoProductionResponse.Line line(ProductionOrderItemEntity item, ProductEntity product, ColorEntity color,
                                               LineState state) {
        Map<String, Integer> sizes = sizesOf(item.getSizesData());
        int total = sizes.isEmpty()
                ? (item.getQuantity() == null ? 0 : item.getQuantity())
                : sizes.values().stream().mapToInt(Integer::intValue).sum();
        return CinchoProductionResponse.Line.builder()
                .productionOrderItemId(item.getId())
                .productId(item.getProductId())
                .productCode(product == null ? null : product.getCode())
                .productName(product == null ? null : product.getName())
                .colorName(color == null ? null : color.getName())
                .sizes(sizes)
                .totalQty(total)
                .observations(item.getObservations())
                .workStatus(state.workStatus())
                .delivered(state.delivered())
                .lastWorkDate(state.lastWorkDate())
                .build();
    }

    /**
     * Avance en el listado: el estado de cada línea es lo último que se marcó en ella, en
     * cualquier día; una línea entregada o completada alguna vez cuenta como hecha.
     */
    private void withProgress(List<CinchoProductionResponse.Order> orders) {
        List<Long> itemIds = orders.stream().flatMap(o -> o.getLines().stream())
                .map(CinchoProductionResponse.Line::getProductionOrderItemId).toList();
        if (itemIds.isEmpty()) {
            return;
        }
        Map<Long, List<ProductionCinchoDayStatusEntity>> byItem = statusRepository.findByProductionOrderItemIdIn(itemIds)
                .stream().collect(Collectors.groupingBy(ProductionCinchoDayStatusEntity::getProductionOrderItemId));
        for (CinchoProductionResponse.Order o : orders) {
            for (CinchoProductionResponse.Line l : o.getLines()) {
                List<ProductionCinchoDayStatusEntity> rows = byItem.getOrDefault(l.getProductionOrderItemId(), List.of());
                if (rows.isEmpty()) {
                    continue;
                }
                ProductionCinchoDayStatusEntity last = rows.stream()
                        .max(Comparator.comparing(ProductionCinchoDayStatusEntity::getWorkDate)
                                .thenComparing(ProductionCinchoDayStatusEntity::getId))
                        .orElseThrow();
                boolean everDone = rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.getDelivered())
                        || CinchoProductUtils.WORK_STATUS_COMPLETED.equals(workStatusOf(r)));
                l.setWorkStatus(everDone ? CinchoProductUtils.WORK_STATUS_COMPLETED : workStatusOf(last));
                l.setDelivered(rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.getDelivered())));
                l.setLastWorkDate(last.getWorkDate());
            }
            o.setLinesDone((int) o.getLines().stream().filter(CinchoProductionService::isDone).count());
            o.setLinesInProgress((int) o.getLines().stream()
                    .filter(l -> !isDone(l) && CinchoProductUtils.WORK_STATUS_IN_PROGRESS.equals(l.getWorkStatus()))
                    .count());
        }
    }

    private CinchoProductionResponse.Summary summary(List<CinchoProductionResponse.Order> active) {
        LocalDate today = GuatemalaDateTime.today();
        LocalDate week = today.plusDays(7);
        int units = 0;
        int unitsDone = 0;
        for (CinchoProductionResponse.Order o : active) {
            for (CinchoProductionResponse.Line l : o.getLines()) {
                units += l.getTotalQty();
                if (isDone(l)) {
                    unitsDone += l.getTotalQty();
                }
            }
        }
        return CinchoProductionResponse.Summary.builder()
                .orders(active.size())
                .pending((int) active.stream().filter(o -> !"IN_PROGRESS".equals(upper(o.getStatus()))).count())
                .inProgress((int) active.stream().filter(o -> "IN_PROGRESS".equals(upper(o.getStatus()))).count())
                .late((int) active.stream().filter(o -> o.getDeliveryDate() != null && o.getDeliveryDate().isBefore(today)).count())
                .dueSoon((int) active.stream().filter(o -> o.getDeliveryDate() != null
                        && !o.getDeliveryDate().isBefore(today) && !o.getDeliveryDate().isAfter(week)).count())
                .units(units)
                .unitsDone(unitsDone)
                .build();
    }

    private Map<Long, ProductionOrderGroupEntity> activeGroups(List<Long> orderIds) {
        List<ProductionOrderGroupMemberEntity> members = groupMemberRepository.findByProductionOrderIdIn(orderIds);
        if (members.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductionOrderGroupEntity> groups = byId(
                members.stream().map(ProductionOrderGroupMemberEntity::getGroupId).collect(Collectors.toSet()),
                groupRepository::findAllById, ProductionOrderGroupEntity::getId);
        Map<Long, ProductionOrderGroupEntity> out = new HashMap<>();
        for (ProductionOrderGroupMemberEntity m : members) {
            ProductionOrderGroupEntity g = groups.get(m.getGroupId());
            if (g != null && ProductionOrderGroupEntity.ACTIVE.equals(g.getStatus())) {
                out.put(m.getProductionOrderId(), g);
            }
        }
        return out;
    }

    // ==================== AYUDAS ====================

    private static List<String> allTypes() {
        List<String> all = new ArrayList<>(OPC_TYPES);
        all.addAll(SALE_TYPES);
        return all;
    }

    private static <T> Map<Long, T> byId(Set<Long> ids, Function<Collection<Long>, List<T>> loader, Function<T, Long> idOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids).stream().collect(Collectors.toMap(idOf, Function.identity(), (a, b) -> a));
    }

    static String familyOf(String orderType) {
        return switch (upper(orderType)) {
            case "VENTA_EN_LINEA" -> "OPL";
            case "CLIENTE_KIOSKO" -> "OPCK";
            case "CINCHOS_FOSSILES" -> "OPCF";
            case "CINCHOS_MARCAS" -> "OPCM";
            default -> "OPC";
        };
    }

    private static boolean isDone(CinchoProductionResponse.Line l) {
        return l.isDelivered() || CinchoProductUtils.WORK_STATUS_COMPLETED.equals(l.getWorkStatus());
    }

    private static String workStatusOf(ProductionCinchoDayStatusEntity s) {
        String w = upper(s.getWorkStatus());
        return CinchoProductUtils.WORK_STATUS_IN_PROGRESS.equals(w) || CinchoProductUtils.WORK_STATUS_COMPLETED.equals(w)
                ? w : CinchoProductUtils.WORK_STATUS_PENDING;
    }

    /** Tallas con unidades, en orden: numéricas de menor a mayor, luego XS…XXXL, luego el resto. */
    Map<String, Integer> sizesOf(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> raw;
        try {
            raw = objectMapper.readValue(json, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        raw.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.comparingByKey(CinchoProductionService::compareSizes))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    static int compareSizes(String a, String b) {
        Double na = number(a);
        Double nb = number(b);
        if (na != null && nb != null) {
            return Double.compare(na, nb);
        }
        if (na != null) {
            return -1;
        }
        if (nb != null) {
            return 1;
        }
        int ia = SIZE_ORDER.indexOf(upper(a));
        int ib = SIZE_ORDER.indexOf(upper(b));
        if (ia >= 0 && ib >= 0) {
            return Integer.compare(ia, ib);
        }
        if (ia >= 0) {
            return -1;
        }
        if (ib >= 0) {
            return 1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }

    private static Double number(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    /** Sin las líneas internas que la OP guarda en sus observaciones (empaque, envío). */
    private static String cleanObservations(String raw) {
        if (raw == null) {
            return null;
        }
        String clean = raw.lines().filter(l -> !l.startsWith("__")).collect(Collectors.joining("\n")).trim();
        return clean.isEmpty() ? null : clean;
    }

    private static List<String> words(String search) {
        if (search == null || search.isBlank()) {
            return List.of();
        }
        return List.of(normalize(search).split("\\s+")).stream().filter(w -> !w.isBlank()).toList();
    }

    private static boolean matches(CinchoProductionResponse.Order o, List<String> words) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.getCode()).append(' ').append(o.getCustomerName()).append(' ').append(o.getSellerName())
                .append(' ').append(o.getGroupCode()).append(' ').append(o.getFamily());
        for (CinchoProductionResponse.Line l : o.getLines()) {
            sb.append(' ').append(l.getProductCode()).append(' ').append(l.getProductName()).append(' ').append(l.getColorName());
        }
        String text = normalize(sb.toString());
        return words.stream().allMatch(text::contains);
    }

    private static String normalize(String s) {
        return Normalizer.normalize(String.valueOf(s).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
