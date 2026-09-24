package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderMaterialsEstimateResponse;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.BomItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UomEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.BomRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UomRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Materiales requeridos por una OP: cantidad BOM × piezas de cada línea
 * (mismo criterio de receta que el consumo de materiales).
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderMaterialsEstimateService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final BomRepository bomRepository;
    private final MaterialRepository materialRepository;
    private final UomRepository uomRepository;
    private final InventoryService inventoryService;
    private final MaterialConsumptionService materialConsumptionService;

    @Transactional(readOnly = true)
    public ProductionOrderMaterialsEstimateResponse estimate(Long productionOrderId)
            throws ResourceNotFoundException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(order.getId());
        Set<Long> productIds = items.stream()
                .map(ProductionOrderItemEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> colorIds = items.stream()
                .map(ProductionOrderItemEntity::getColorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, ProductEntity> productsById = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
        Map<Long, ColorEntity> colorsById = colorIds.isEmpty()
                ? Map.of()
                : colorRepository.findAllById(colorIds).stream()
                        .collect(Collectors.toMap(ColorEntity::getId, c -> c, (a, b) -> a));

        Map<Long, MaterialEntity> materialsById = new HashMap<>();
        Map<Long, BigDecimal> availableByMaterial = new HashMap<>();
        Map<Long, String> uomCodeById = new HashMap<>();

        List<ProductionOrderMaterialsEstimateResponse.LineEstimate> lines = new ArrayList<>();
        Map<Long, ProductionOrderMaterialsEstimateResponse.MaterialSummary> byMaterial = new LinkedHashMap<>();

        int totalUnits = 0;
        int missingBom = 0;
        int skippedNoMaterials = 0;

        for (ProductionOrderItemEntity item : items) {
            ProductEntity product = item.getProductId() != null ? productsById.get(item.getProductId()) : null;
            ColorEntity color = item.getColorId() != null ? colorsById.get(item.getColorId()) : null;
            int qty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            totalUnits += qty;

            String colorName = color != null
                    ? color.getName()
                    : (item.getColorId() != null ? "#" + item.getColorId() : "Sin color");
            String productCode = product != null ? product.getCode() : null;
            String productName = product != null ? product.getName() : null;

            if (item.getProductId() != null && !materialConsumptionService.productRequiresMaterials(item.getProductId())) {
                skippedNoMaterials++;
                lines.add(ProductionOrderMaterialsEstimateResponse.LineEstimate.builder()
                        .itemId(item.getId())
                        .productId(item.getProductId())
                        .productCode(productCode)
                        .productName(productName)
                        .colorId(item.getColorId())
                        .colorName(colorName)
                        .quantity(qty)
                        .missingBom(false)
                        .note("Producto sin materiales requeridos")
                        .build());
                continue;
            }

            BomEntity bom = resolveBomForOrderItem(item);
            if (bom == null || bom.getItems() == null || bom.getItems().isEmpty()) {
                missingBom++;
                lines.add(ProductionOrderMaterialsEstimateResponse.LineEstimate.builder()
                        .itemId(item.getId())
                        .productId(item.getProductId())
                        .productCode(productCode)
                        .productName(productName)
                        .colorId(item.getColorId())
                        .colorName(colorName)
                        .quantity(qty)
                        .missingBom(true)
                        .note("Sin receta (BOM) activa")
                        .build());
                continue;
            }

            for (BomItemEntity bi : bom.getItems()) {
                if (bi.getMaterialId() == null || bi.getQuantity() == null) {
                    continue;
                }
                BigDecimal qtyPerUnit = bi.getQuantity().setScale(3, RoundingMode.HALF_UP);
                BigDecimal totalQty = qtyPerUnit.multiply(BigDecimal.valueOf(qty)).setScale(3, RoundingMode.HALF_UP);

                MaterialEntity material = materialsById.computeIfAbsent(bi.getMaterialId(),
                        id -> materialRepository.findById(id).orElse(null));
                BigDecimal available = availableByMaterial.computeIfAbsent(bi.getMaterialId(), this::getBestAvailableQuantity);
                BigDecimal toOrder = totalQty.subtract(available).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
                String unit = resolveUnit(bi, material, uomCodeById);

                lines.add(ProductionOrderMaterialsEstimateResponse.LineEstimate.builder()
                        .itemId(item.getId())
                        .productId(item.getProductId())
                        .productCode(productCode)
                        .productName(productName)
                        .colorId(item.getColorId())
                        .colorName(colorName)
                        .quantity(qty)
                        .bomId(bom.getId())
                        .bomName(bom.getBomName())
                        .materialId(bi.getMaterialId())
                        .materialSku(material != null ? material.getSku() : null)
                        .materialName(material != null ? material.getName() : ("#" + bi.getMaterialId()))
                        .qtyPerUnit(qtyPerUnit)
                        .totalQty(totalQty)
                        .unit(unit)
                        .available(available)
                        .toOrder(toOrder)
                        .missingBom(false)
                        .build());

                MaterialEntity mat = material;
                BigDecimal avail = available;
                String unitLabel = unit;
                ProductionOrderMaterialsEstimateResponse.MaterialSummary matSum = byMaterial.computeIfAbsent(
                        bi.getMaterialId(),
                        id -> ProductionOrderMaterialsEstimateResponse.MaterialSummary.builder()
                                .materialId(id)
                                .materialSku(mat != null ? mat.getSku() : null)
                                .materialName(mat != null ? mat.getName() : ("#" + id))
                                .unit(unitLabel)
                                .requiredQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP))
                                .availableQty(avail)
                                .toOrderQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP))
                                .sufficient(true)
                                .build());
                matSum.setRequiredQty(matSum.getRequiredQty().add(totalQty));
            }
        }

        for (ProductionOrderMaterialsEstimateResponse.MaterialSummary matSum : byMaterial.values()) {
            BigDecimal required = matSum.getRequiredQty().setScale(3, RoundingMode.HALF_UP);
            BigDecimal available = matSum.getAvailableQty() != null
                    ? matSum.getAvailableQty()
                    : BigDecimal.ZERO;
            BigDecimal shortage = required.subtract(available).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
            matSum.setRequiredQty(required);
            matSum.setToOrderQty(shortage);
            matSum.setSufficient(shortage.compareTo(BigDecimal.ZERO) == 0);
        }

        List<ProductionOrderMaterialsEstimateResponse.MaterialSummary> materialSummaries = byMaterial.values().stream()
                .sorted(Comparator
                        .comparing(ProductionOrderMaterialsEstimateResponse.MaterialSummary::isSufficient)
                        .thenComparing(
                                ProductionOrderMaterialsEstimateResponse.MaterialSummary::getMaterialName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        return ProductionOrderMaterialsEstimateResponse.builder()
                .productionOrderId(order.getId())
                .productionOrderCode(order.getCode())
                .orderType(order.getOrderType())
                .status(order.getStatus())
                .totalUnits(totalUnits)
                .materialCount(materialSummaries.size())
                .linesMissingBom(missingBom)
                .linesSkippedNoMaterials(skippedNoMaterials)
                .lines(lines)
                .byMaterial(materialSummaries)
                .build();
    }

    private BomEntity resolveBomForOrderItem(ProductionOrderItemEntity item) {
        if (item == null || item.getProductId() == null) {
            return null;
        }
        List<BomEntity> boms = bomRepository.findByProductIdAndStatus(item.getProductId(), "A");
        if (boms.isEmpty()) {
            return null;
        }
        Long colorId = item.getColorId();
        return boms.stream()
                .filter(b -> colorId != null && colorId.equals(b.getColorId()))
                .findFirst()
                .orElseGet(() -> boms.stream()
                        .filter(b -> b.getColorId() == null)
                        .findFirst()
                        .orElseGet(() -> boms.stream().findFirst().orElse(null)));
    }

    private BigDecimal getBestAvailableQuantity(Long materialId) {
        BigDecimal inventoryQty = BigDecimal.ZERO;
        try {
            inventoryQty = inventoryService.getMaterialInventory(materialId).getTotalQuantity();
        } catch (ResourceNotFoundException ignored) {
            // sin inventario registrado
        }
        BigDecimal materialQty = materialRepository.findById(materialId)
                .map(m -> m.getQuantity() != null ? m.getQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
        return inventoryQty.max(materialQty).setScale(3, RoundingMode.HALF_UP);
    }

    private String resolveUnit(BomItemEntity bi, MaterialEntity material, Map<Long, String> uomCodeById) {
        if (bi.getMeasurementUnit() != null && !bi.getMeasurementUnit().isBlank()) {
            return bi.getMeasurementUnit().trim();
        }
        Long uomId = material != null
                ? (material.getManufacturingUomId() != null ? material.getManufacturingUomId() : material.getUomId())
                : null;
        if (uomId == null) {
            return null;
        }
        return uomCodeById.computeIfAbsent(uomId, id ->
                uomRepository.findById(id).map(UomEntity::getCode).orElse(null));
    }
}
