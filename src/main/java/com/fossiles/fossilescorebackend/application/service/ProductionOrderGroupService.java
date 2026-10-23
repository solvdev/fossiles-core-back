package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderGroupRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderGroupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupMemberEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupMemberRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OP consolidada del Centro de Producción: varias OPV (o varias OPC) de Luis Felipe que se
 * producen como una sola porque cada una es muy pequeña.
 *
 * <p>Las órdenes originales no se tocan: cada una conserva su envío, su cobro en cartera y su
 * empaque. La consolidada solo las agrupa para planificar («Armar el día» arma tareas con
 * productos de todas), imprimir una hoja con los productos sumados y trabajarlas en mesa.
 * OPV y OPC no se mezclan: los cinchos van a su propia mesa.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderGroupService {

    public static final String OPV = "OPV";
    public static final String OPC = "OPC";
    private static final Set<String> CLOSED = Set.of("COMPLETED", "CANCELLED");

    private final ProductionOrderGroupRepository groupRepository;
    private final ProductionOrderGroupMemberRepository memberRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final SecurityUtil securityUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OPV u OPC si la orden es de Luis Felipe y se puede consolidar; null si no. */
    public static String familyOf(ProductionOrderEntity order) {
        if (order == null) {
            return null;
        }
        if (ProductionPlanningConstants.isLuisFelipeVendorOrder(order.getSellerName(), order.getOrderType(), order.getCode())) {
            return OPV;
        }
        if (ProductionPlanningConstants.isLuisFelipeCinchoOrder(order.getSellerName(), order.getOrderType(), order.getCode())) {
            return OPC;
        }
        return null;
    }

    // ==================== crear y desagrupar ====================

    @Transactional(rollbackFor = Exception.class)
    public ProductionOrderGroupResponse create(ProductionOrderGroupRequest request) throws BusinessException {
        List<Long> ids = request == null || request.getProductionOrderIds() == null ? List.of()
                : request.getProductionOrderIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() < 2) {
            throw new BusinessException("Elija al menos dos órdenes para consolidar.");
        }
        Map<Long, ProductionOrderEntity> byId = productionOrderRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(ProductionOrderEntity::getId, o -> o));
        List<ProductionOrderEntity> orders = new ArrayList<>();
        for (Long id : ids) {
            ProductionOrderEntity o = byId.get(id);
            if (o == null) {
                throw new BusinessException("La orden " + id + " no existe.");
            }
            orders.add(o);
        }
        String family = null;
        for (ProductionOrderEntity o : orders) {
            String f = familyOf(o);
            if (f == null) {
                throw new BusinessException("Solo se consolidan OPV u OPC de Luis Felipe; " + o.getCode() + " no lo es.");
            }
            if (family != null && !family.equals(f)) {
                throw new BusinessException("No se mezclan OPV y OPC en una consolidada: los cinchos van a su propia mesa.");
            }
            family = f;
            if (CLOSED.contains(upper(o.getStatus()))) {
                throw new BusinessException("La orden " + o.getCode() + " ya está " + o.getStatus() + ".");
            }
        }
        List<ProductionOrderGroupMemberEntity> taken = memberRepository.findByProductionOrderIdIn(ids);
        if (!taken.isEmpty()) {
            Map<Long, String> groupCodes = groupRepository.findAllById(
                            taken.stream().map(ProductionOrderGroupMemberEntity::getGroupId).collect(Collectors.toSet()))
                    .stream().collect(Collectors.toMap(ProductionOrderGroupEntity::getId, ProductionOrderGroupEntity::getCode));
            String detail = taken.stream()
                    .map(m -> byId.get(m.getProductionOrderId()).getCode() + " (en " + groupCodes.get(m.getGroupId()) + ")")
                    .collect(Collectors.joining(", "));
            throw new BusinessException("Estas órdenes ya están en otra consolidada: " + detail + ".");
        }

        orders.sort(Comparator.comparing(ProductionOrderEntity::getId));
        String memberCodes = orders.stream().map(ProductionOrderEntity::getCode).collect(Collectors.joining(", "));
        var now = GuatemalaDateTime.now();
        ProductionOrderGroupEntity group = groupRepository.save(ProductionOrderGroupEntity.builder()
                .code("TMP-" + UUID.randomUUID())
                .family(family)
                .status(ProductionOrderGroupEntity.ACTIVE)
                .observations(trim500(request.getObservations()))
                .memberCodes(memberCodes.length() > 1000 ? memberCodes.substring(0, 1000) : memberCodes)
                .createdBy(currentUserId())
                .createdAt(now)
                .build());
        // El código lleva el id: único sin contador aparte.
        group.setCode(String.format(Locale.ROOT, "LF-%s-%03d", family, group.getId()));
        group = groupRepository.save(group);
        for (ProductionOrderEntity o : orders) {
            memberRepository.save(ProductionOrderGroupMemberEntity.builder()
                    .groupId(group.getId()).productionOrderId(o.getId()).createdAt(now).build());
        }
        return toResponse(group, orders, true);
    }

    /** Desagrupa: las órdenes vuelven a planificarse sueltas. Las tareas ya creadas no cambian. */
    @Transactional(rollbackFor = Exception.class)
    public ProductionOrderGroupResponse dissolve(Long groupId) throws ResourceNotFoundException, BusinessException {
        ProductionOrderGroupEntity group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("OP consolidada", groupId));
        if (!ProductionOrderGroupEntity.ACTIVE.equals(group.getStatus())) {
            throw new BusinessException("La consolidada " + group.getCode() + " ya estaba desagrupada.");
        }
        List<ProductionOrderEntity> orders = ordersOf(group.getId());
        memberRepository.deleteByGroupId(group.getId());
        group.setStatus(ProductionOrderGroupEntity.DISSOLVED);
        group.setDissolvedAt(GuatemalaDateTime.now());
        group.setDissolvedBy(currentUserId());
        return toResponse(groupRepository.save(group), orders, false);
    }

    // ==================== consultas ====================

    /** Consolidadas activas con sus órdenes (sin productos). */
    @Transactional(readOnly = true)
    public List<ProductionOrderGroupResponse> listActive() {
        List<ProductionOrderGroupEntity> groups = groupRepository.findByStatusOrderByCreatedAtDesc(ProductionOrderGroupEntity.ACTIVE);
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> orderIdsByGroup = new HashMap<>();
        memberRepository.findByGroupIdIn(groups.stream().map(ProductionOrderGroupEntity::getId).toList())
                .forEach(m -> orderIdsByGroup.computeIfAbsent(m.getGroupId(), k -> new ArrayList<>()).add(m.getProductionOrderId()));
        Set<Long> allIds = orderIdsByGroup.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        Map<Long, ProductionOrderEntity> orders = productionOrderRepository.findAllById(allIds).stream()
                .collect(Collectors.toMap(ProductionOrderEntity::getId, o -> o));
        List<ProductionOrderGroupResponse> out = new ArrayList<>();
        for (ProductionOrderGroupEntity g : groups) {
            List<ProductionOrderEntity> members = orderIdsByGroup.getOrDefault(g.getId(), List.of()).stream()
                    .map(orders::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ProductionOrderEntity::getId)).toList();
            out.add(toResponse(g, members, false));
        }
        return out;
    }

    /** Detalle con los productos sumados, para la hoja de la OP consolidada. */
    @Transactional(readOnly = true)
    public ProductionOrderGroupResponse get(Long groupId) throws ResourceNotFoundException {
        ProductionOrderGroupEntity group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("OP consolidada", groupId));
        return toResponse(group, ordersOf(group.getId()), true);
    }

    /** Órdenes abiertas de Luis Felipe que todavía no están en ninguna consolidada. */
    @Transactional(readOnly = true)
    public List<ProductionOrderGroupResponse.OrderRef> candidates(String family) {
        String wanted = family == null ? null : family.trim().toUpperCase(Locale.ROOT);
        List<ProductionOrderEntity> open = productionOrderRepository.findOpenLuisFelipeOrders().stream()
                .filter(o -> familyOf(o) != null && (wanted == null || wanted.isEmpty() || wanted.equals(familyOf(o))))
                .toList();
        Set<Long> grouped = memberRepository.groupIdByOrderId(open.stream().map(ProductionOrderEntity::getId).toList()).keySet();
        List<ProductionOrderEntity> free = open.stream().filter(o -> !grouped.contains(o.getId())).toList();
        Map<Long, Integer> units = unitsByOrder(free.stream().map(ProductionOrderEntity::getId).toList());
        return free.stream().map(o -> orderRef(o, units.getOrDefault(o.getId(), 0))).toList();
    }

    /** OP → código de su consolidada activa, para quien lista órdenes. */
    @Transactional(readOnly = true)
    public Map<Long, ProductionOrderGroupEntity> activeGroupByOrderId(Collection<Long> orderIds) {
        Map<Long, Long> groupIds = memberRepository.groupIdByOrderId(orderIds);
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductionOrderGroupEntity> groups = groupRepository.findAllById(new HashSet<>(groupIds.values())).stream()
                .collect(Collectors.toMap(ProductionOrderGroupEntity::getId, g -> g));
        Map<Long, ProductionOrderGroupEntity> out = new HashMap<>();
        groupIds.forEach((orderId, groupId) -> {
            ProductionOrderGroupEntity g = groups.get(groupId);
            if (g != null && ProductionOrderGroupEntity.ACTIVE.equals(g.getStatus())) {
                out.put(orderId, g);
            }
        });
        return out;
    }

    // ==================== armado de la respuesta ====================

    private List<ProductionOrderEntity> ordersOf(Long groupId) {
        List<Long> ids = memberRepository.findByGroupIdOrderByIdAsc(groupId).stream()
                .map(ProductionOrderGroupMemberEntity::getProductionOrderId).toList();
        return productionOrderRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(ProductionOrderEntity::getId)).toList();
    }

    private ProductionOrderGroupResponse toResponse(ProductionOrderGroupEntity g, List<ProductionOrderEntity> orders,
                                                    boolean withLines) {
        List<Long> ids = orders.stream().map(ProductionOrderEntity::getId).toList();
        List<ProductionOrderItemEntity> items = ids.isEmpty() ? List.of()
                : ids.stream().flatMap(id -> productionOrderItemRepository.findByProductionOrderId(id).stream()).toList();
        Map<Long, Integer> units = new HashMap<>();
        items.forEach(i -> units.merge(i.getProductionOrderId(), ProductionOrderItemQuantityHelper.effectiveQuantityForBom(i), Integer::sum));
        List<ProductionOrderGroupResponse.OrderRef> refs = orders.stream()
                .map(o -> orderRef(o, units.getOrDefault(o.getId(), 0))).toList();
        LocalDate delivery = orders.stream().map(ProductionOrderEntity::getDeliveryDate).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        return ProductionOrderGroupResponse.builder()
                .id(g.getId()).code(g.getCode()).family(g.getFamily()).status(g.getStatus())
                .observations(g.getObservations()).memberCodes(g.getMemberCodes())
                .createdAt(g.getCreatedAt()).createdBy(g.getCreatedBy()).dissolvedAt(g.getDissolvedAt())
                .deliveryDate(delivery)
                .totalUnits(units.values().stream().mapToInt(Integer::intValue).sum())
                .orders(refs)
                .lines(withLines ? sumLines(items, orders) : null)
                .build();
    }

    private static ProductionOrderGroupResponse.OrderRef orderRef(ProductionOrderEntity o, int units) {
        return ProductionOrderGroupResponse.OrderRef.builder()
                .id(o.getId()).code(o.getCode()).orderType(o.getOrderType()).status(o.getStatus())
                .customerName(o.getCustomerName()).deliveryDate(o.getDeliveryDate()).units(units).build();
    }

    private Map<Long, Integer> unitsByOrder(List<Long> orderIds) {
        Map<Long, Integer> out = new HashMap<>();
        for (Long id : orderIds) {
            productionOrderItemRepository.findByProductionOrderId(id).forEach(i ->
                    out.merge(id, ProductionOrderItemQuantityHelper.effectiveQuantityForBom(i), Integer::sum));
        }
        return out;
    }

    /** Suma por producto + color + marca: cantidades, tallas y de qué OP sale cada parte. */
    private List<ProductionOrderGroupResponse.Line> sumLines(List<ProductionOrderItemEntity> items,
                                                             List<ProductionOrderEntity> orders) {
        Map<Long, String> codeById = orders.stream()
                .collect(Collectors.toMap(ProductionOrderEntity::getId, ProductionOrderEntity::getCode));
        Map<Long, ProductEntity> products = productRepository.findAllById(items.stream().map(ProductionOrderItemEntity::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
        Map<Long, String> colors = colorRepository.findAllById(items.stream().map(ProductionOrderItemEntity::getColorId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ColorEntity::getId, ColorEntity::getName, (a, b) -> a));

        Map<String, ProductionOrderGroupResponse.Line> byKey = new LinkedHashMap<>();
        for (ProductionOrderItemEntity item : items) {
            int qty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            if (qty <= 0) {
                continue;
            }
            String key = item.getProductId() + "|" + item.getColorId() + "|" + Objects.toString(item.getBrandName(), "");
            ProductEntity p = item.getProductId() == null ? null : products.get(item.getProductId());
            ProductionOrderGroupResponse.Line line = byKey.computeIfAbsent(key, k -> ProductionOrderGroupResponse.Line.builder()
                    .productId(item.getProductId())
                    .productCode(p == null ? null : p.getCode())
                    .productName(p == null ? null : p.getName())
                    .colorId(item.getColorId())
                    .colorName(item.getColorId() == null ? null : colors.get(item.getColorId()))
                    .brandName(item.getBrandName())
                    .quantity(0)
                    .sizes(new TreeMap<>())
                    .byOrder(new LinkedHashMap<>())
                    .build());
            line.setQuantity(line.getQuantity() + qty);
            line.getByOrder().merge(codeById.getOrDefault(item.getProductionOrderId(), "?"), qty, Integer::sum);
            parseSizes(item.getSizesData()).forEach((size, n) -> line.getSizes().merge(size, n, Integer::sum));
        }
        List<ProductionOrderGroupResponse.Line> lines = new ArrayList<>(byKey.values());
        lines.sort(Comparator.comparing((ProductionOrderGroupResponse.Line l) -> Objects.toString(l.getProductCode(), ""))
                .thenComparing(l -> Objects.toString(l.getColorName(), "")));
        return lines;
    }

    private Map<String, Integer> parseSizes(String sizesData) {
        if (sizesData == null || sizesData.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Integer> raw = objectMapper.readValue(sizesData, new TypeReference<Map<String, Integer>>() {});
            Map<String, Integer> out = new HashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v != null && v > 0) {
                    out.put(k, v);
                }
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Long currentUserId() {
        try {
            return securityUtil.getCurrentUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim500(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }
}
