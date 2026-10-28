package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionLeatherDemandResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionLeatherDemandResponse.ColorDemand;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionLeatherDemandResponse.MaterialDemand;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionLeatherDemandResponse.OrderDemand;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionLeatherDemandResponse.OrderLine;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cuero que piden todas las OP activas (pendientes, en proceso, en QA), por color, por cuero
 * y por orden. Una pieza ya «entregada» es la que tiene su cuero en mesa: tarea iniciada o
 * terminada, o cuero marcado como entregado. Lo demás es lo que falta entregar.
 */
@Service
@RequiredArgsConstructor
public class ProductionLeatherDemandService {

    static final List<String> ACTIVE_STATUSES = List.of("PENDING", "IN_PROGRESS", "IN_QA");

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final MaterialRepository materialRepository;
    private final LeatherInventoryRepository leatherInventoryRepository;
    private final LeatherRequirementService leatherRequirementService;

    private record RecipeKey(Long productId, Long colorId) {}

    @Transactional(readOnly = true)
    public ProductionLeatherDemandResponse report() {
        List<ProductionOrderEntity> orders = productionOrderRepository.findByStatusIn(ACTIVE_STATUSES);
        Set<Long> orderIds = orders.stream().map(ProductionOrderEntity::getId).collect(Collectors.toSet());
        List<ProductionOrderItemEntity> items = orderIds.isEmpty()
                ? List.of()
                : productionOrderItemRepository.findByProductionOrderIdIn(orderIds);

        Map<Long, List<ProductionOrderItemEntity>> itemsByOrder = items.stream()
                .collect(Collectors.groupingBy(ProductionOrderItemEntity::getProductionOrderId));
        Map<Long, ProductEntity> products = byId(productRepository.findAllById(ids(items, ProductionOrderItemEntity::getProductId)),
                ProductEntity::getId);
        Map<Long, ColorEntity> colors = byId(colorRepository.findAllById(ids(items, ProductionOrderItemEntity::getColorId)),
                ColorEntity::getId);
        Map<Long, Integer> deliveredByItem = deliveredUnitsByItem(items);

        // Receta por (producto, color): misma regla que Armar el día, una consulta por combinación.
        Map<RecipeKey, LeatherRequirementService.LeatherNeed> recipes = new HashMap<>();
        for (ProductionOrderItemEntity item : items) {
            recipes.computeIfAbsent(new RecipeKey(item.getProductId(), item.getColorId()),
                    k -> leatherRequirementService.resolvePerUnit(products.get(k.productId()), k.colorId()));
        }
        Set<Long> materialIds = recipes.values().stream()
                .map(LeatherRequirementService.LeatherNeed::materialId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, MaterialEntity> materials = byId(materialRepository.findAllById(materialIds), MaterialEntity::getId);
        Map<Long, BigDecimal> stock = new HashMap<>();
        if (!materialIds.isEmpty()) {
            for (LeatherInventoryEntity inv : leatherInventoryRepository.findByMaterialIdIn(materialIds)) {
                stock.merge(inv.getMaterialId(), nz(inv.getQuantityAvailable()), BigDecimal::add);
            }
        }

        Map<String, ColorAcc> colorAcc = new LinkedHashMap<>();
        Map<Long, MaterialAcc> materialAcc = new LinkedHashMap<>();
        List<OrderDemand> orderRows = new ArrayList<>();
        Totals totals = new Totals();

        for (ProductionOrderEntity order : orders) {
            List<ProductionOrderItemEntity> orderItems = itemsByOrder.getOrDefault(order.getId(), List.of());
            if (orderItems.isEmpty()) {
                continue;
            }
            List<OrderLine> lines = new ArrayList<>();
            int units = 0;
            int pendingUnits = 0;
            int missingLines = 0;
            BigDecimal totalFt2 = BigDecimal.ZERO;
            BigDecimal pendingFt2 = BigDecimal.ZERO;

            for (ProductionOrderItemEntity item : orderItems) {
                ProductEntity product = products.get(item.getProductId());
                ColorEntity color = colors.get(item.getColorId());
                String colorName = color != null && color.getName() != null ? color.getName().trim()
                        : (item.getColorId() != null ? "#" + item.getColorId() : "SIN COLOR");
                int qty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
                int delivered = Math.min(deliveredByItem.getOrDefault(item.getId(), 0), qty);
                int pending = qty - delivered;

                LeatherRequirementService.LeatherNeed need = recipes.get(new RecipeKey(item.getProductId(), item.getColorId()));
                boolean missing = need == null || need.blocked();
                BigDecimal perUnit = missing ? null : nz(need.qtyFt2());
                BigDecimal lineFt2 = missing ? BigDecimal.ZERO : perUnit.multiply(BigDecimal.valueOf(qty));
                BigDecimal linePending = missing ? BigDecimal.ZERO : perUnit.multiply(BigDecimal.valueOf(pending));
                MaterialEntity material = missing || need.materialId() == null ? null : materials.get(need.materialId());

                units += qty;
                pendingUnits += pending;
                totalFt2 = totalFt2.add(lineFt2);
                pendingFt2 = pendingFt2.add(linePending);
                if (missing) {
                    missingLines++;
                }

                ColorAcc ca = colorAcc.computeIfAbsent(colorName.toUpperCase(), k -> new ColorAcc(item.getColorId(), colorName));
                ca.orders.add(order.getId());
                ca.units += qty;
                ca.pendingUnits += pending;
                ca.totalFt2 = ca.totalFt2.add(lineFt2);
                ca.pendingFt2 = ca.pendingFt2.add(linePending);
                if (missing) {
                    ca.missingRecipeUnits += qty;
                }
                if (material != null) {
                    ca.materialIds.add(material.getId());
                    MaterialAcc ma = materialAcc.computeIfAbsent(material.getId(), k -> new MaterialAcc(material));
                    ma.orders.add(order.getId());
                    ma.totalFt2 = ma.totalFt2.add(lineFt2);
                    ma.pendingFt2 = ma.pendingFt2.add(linePending);
                }

                totals.missingRecipeLines += missing ? 1 : 0;
                totals.missingRecipeUnits += missing ? qty : 0;
                totals.deliveredUnits += delivered;

                lines.add(OrderLine.builder()
                        .itemId(item.getId())
                        .productCode(product != null ? product.getCode() : null)
                        .productName(product != null ? product.getName() : null)
                        .colorId(item.getColorId())
                        .colorName(colorName)
                        .materialId(material != null ? material.getId() : null)
                        .materialName(material != null ? material.getName() : null)
                        .units(qty)
                        .deliveredUnits(delivered)
                        .pendingUnits(pending)
                        .ft2PerUnit(perUnit != null ? perUnit.setScale(3, RoundingMode.HALF_UP) : null)
                        .totalFt2(ft2(lineFt2))
                        .pendingFt2(ft2(linePending))
                        .missingRecipe(missing)
                        .build());
            }

            totals.orders++;
            totals.units += units;
            totals.pendingUnits += pendingUnits;
            totals.totalFt2 = totals.totalFt2.add(totalFt2);
            totals.pendingFt2 = totals.pendingFt2.add(pendingFt2);

            orderRows.add(OrderDemand.builder()
                    .orderId(order.getId())
                    .code(order.getCode())
                    .orderType(order.getOrderType())
                    .status(order.getStatus())
                    .customerName(order.getCustomerName() != null ? order.getCustomerName() : order.getSellerName())
                    .deliveryDate(order.getDeliveryDate())
                    .units(units)
                    .pendingUnits(pendingUnits)
                    .totalFt2(ft2(totalFt2))
                    .deliveredFt2(ft2(totalFt2.subtract(pendingFt2)))
                    .pendingFt2(ft2(pendingFt2))
                    .missingRecipeLines(missingLines)
                    .lines(lines)
                    .build());
        }

        List<MaterialDemand> materialRows = materialAcc.values().stream()
                .map(ma -> {
                    BigDecimal available = stock.getOrDefault(ma.material.getId(), BigDecimal.ZERO);
                    return MaterialDemand.builder()
                            .materialId(ma.material.getId())
                            .sku(ma.material.getSku())
                            .name(ma.material.getName())
                            .orders(ma.orders.size())
                            .totalFt2(ft2(ma.totalFt2))
                            .pendingFt2(ft2(ma.pendingFt2))
                            .availableFt2(ft2(available))
                            .shortageFt2(ft2(ma.pendingFt2.subtract(available).max(BigDecimal.ZERO)))
                            .build();
                })
                .sorted(Comparator.comparing(MaterialDemand::getPendingFt2).reversed())
                .toList();
        BigDecimal shortage = materialRows.stream().map(MaterialDemand::getShortageFt2)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ColorDemand> colorRows = colorAcc.values().stream()
                .map(ca -> {
                    BigDecimal available = ca.materialIds.stream()
                            .map(id -> stock.getOrDefault(id, BigDecimal.ZERO))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return ColorDemand.builder()
                            .colorId(ca.colorId)
                            .colorName(ca.colorName)
                            .orders(ca.orders.size())
                            .units(ca.units)
                            .pendingUnits(ca.pendingUnits)
                            .totalFt2(ft2(ca.totalFt2))
                            .deliveredFt2(ft2(ca.totalFt2.subtract(ca.pendingFt2)))
                            .pendingFt2(ft2(ca.pendingFt2))
                            .availableFt2(ft2(available))
                            .shortageFt2(ft2(ca.pendingFt2.subtract(available).max(BigDecimal.ZERO)))
                            .missingRecipeUnits(ca.missingRecipeUnits)
                            .materials(ca.materialIds.stream()
                                    .map(materials::get)
                                    .filter(Objects::nonNull)
                                    .map(MaterialEntity::getName)
                                    .toList())
                            .build();
                })
                .sorted(Comparator.comparing(ColorDemand::getPendingFt2).reversed()
                        .thenComparing(ColorDemand::getTotalFt2, Comparator.reverseOrder()))
                .toList();

        orderRows.sort(Comparator.comparing(OrderDemand::getDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OrderDemand::getCode, Comparator.nullsLast(Comparator.naturalOrder())));

        return ProductionLeatherDemandResponse.builder()
                .generatedAt(GuatemalaDateTime.now())
                .totals(ProductionLeatherDemandResponse.Totals.builder()
                        .orders(totals.orders)
                        .units(totals.units)
                        .deliveredUnits(totals.deliveredUnits)
                        .pendingUnits(totals.pendingUnits)
                        .totalFt2(ft2(totals.totalFt2))
                        .deliveredFt2(ft2(totals.totalFt2.subtract(totals.pendingFt2)))
                        .pendingFt2(ft2(totals.pendingFt2))
                        .shortageFt2(ft2(shortage))
                        .missingRecipeLines(totals.missingRecipeLines)
                        .missingRecipeUnits(totals.missingRecipeUnits)
                        .build())
                .byColor(colorRows)
                .byMaterial(materialRows)
                .orders(orderRows)
                .build();
    }

    /**
     * Piezas por línea cuyo cuero ya salió: tarea que no está pendiente (se inició, está en
     * bodega o se terminó) o cuero marcado como entregado en la tarea o en la línea.
     */
    private Map<Long, Integer> deliveredUnitsByItem(List<ProductionOrderItemEntity> items) {
        Map<Long, Integer> out = new HashMap<>();
        if (items.isEmpty()) {
            return out;
        }
        Set<Long> itemIds = items.stream().map(ProductionOrderItemEntity::getId).collect(Collectors.toSet());
        for (Object[] row : taskItemRepository.findLeatherProgressRowsByItemIds(itemIds)) {
            Long itemId = (Long) row[0];
            Integer qty = (Integer) row[1];
            String status = (String) row[2];
            boolean leatherOut = Boolean.TRUE.equals(row[3]) || Boolean.TRUE.equals(row[4])
                    || (status != null && !"PENDING".equals(status));
            if (itemId != null && qty != null && qty > 0 && leatherOut) {
                out.merge(itemId, qty, Integer::sum);
            }
        }
        return out;
    }

    private static <T> Set<Long> ids(List<T> rows, java.util.function.Function<T, Long> getter) {
        return rows.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> byId(List<T> rows, java.util.function.Function<T, Long> getter) {
        return rows.stream().collect(Collectors.toMap(getter, r -> r, (a, b) -> a));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal ft2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Totals {
        int orders;
        int units;
        int deliveredUnits;
        int pendingUnits;
        int missingRecipeLines;
        int missingRecipeUnits;
        BigDecimal totalFt2 = BigDecimal.ZERO;
        BigDecimal pendingFt2 = BigDecimal.ZERO;
    }

    private static final class ColorAcc {
        final Long colorId;
        final String colorName;
        final Set<Long> orders = new HashSet<>();
        final Set<Long> materialIds = new LinkedHashSet<>();
        int units;
        int pendingUnits;
        int missingRecipeUnits;
        BigDecimal totalFt2 = BigDecimal.ZERO;
        BigDecimal pendingFt2 = BigDecimal.ZERO;

        ColorAcc(Long colorId, String colorName) {
            this.colorId = colorId;
            this.colorName = colorName;
        }
    }

    private static final class MaterialAcc {
        final MaterialEntity material;
        final Set<Long> orders = new HashSet<>();
        BigDecimal totalFt2 = BigDecimal.ZERO;
        BigDecimal pendingFt2 = BigDecimal.ZERO;

        MaterialAcc(MaterialEntity material) {
            this.material = material;
        }
    }
}
