package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderLeatherEstimateResponse;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
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
 * Cuero restante por una OP: receta {@code product_variant_leather} del color
 * de cada línea × piezas que aún no tienen el cuero en mesa (misma regla que
 * {@link ProductionLeatherDemandService}).
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderLeatherEstimateService {

    private static final BigDecimal ZERO_FT2 = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final MaterialRepository materialRepository;
    private final LeatherInventoryRepository leatherInventoryRepository;
    private final LeatherRequirementService leatherRequirementService;

    @Transactional(readOnly = true)
    public ProductionOrderLeatherEstimateResponse estimate(Long productionOrderId)
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
        Map<Long, Integer> usedByItem = items.isEmpty()
                ? Map.of()
                : ProductionLeatherDeliveredUnits.fromProgressRows(
                        taskItemRepository.findLeatherProgressRowsByItemIds(
                                items.stream().map(ProductionOrderItemEntity::getId).collect(Collectors.toSet())));

        Map<Long, MaterialEntity> materialsById = new HashMap<>();
        Map<Long, BigDecimal> availableByMaterial = new HashMap<>();

        List<ProductionOrderLeatherEstimateResponse.LineEstimate> lines = new ArrayList<>();
        BigDecimal remainingFt2 = ZERO_FT2;
        BigDecimal usedFt2Total = ZERO_FT2;
        int remainingUnits = 0;
        int totalUnits = 0;
        int usedUnits = 0;
        int missingRecipe = 0;

        Map<String, ProductionOrderLeatherEstimateResponse.ColorSummary> byColor = new LinkedHashMap<>();
        Map<Long, ProductionOrderLeatherEstimateResponse.MaterialSummary> byMaterial = new LinkedHashMap<>();

        for (ProductionOrderItemEntity item : items) {
            ProductEntity product = item.getProductId() != null ? productsById.get(item.getProductId()) : null;
            ColorEntity color = item.getColorId() != null ? colorsById.get(item.getColorId()) : null;
            int qty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            int used = Math.min(usedByItem.getOrDefault(item.getId(), 0), qty);
            int remaining = qty - used;
            totalUnits += qty;
            usedUnits += used;
            remainingUnits += remaining;

            LeatherRequirementService.LeatherNeed need =
                    leatherRequirementService.resolveNeed(product, item.getColorId(), qty);

            boolean missing = need.blocked();
            if (missing) {
                missingRecipe++;
            }

            BigDecimal lineTotalFt2 = missing || need.noneRequired()
                    ? ZERO_FT2
                    : need.qtyFt2().setScale(3, RoundingMode.HALF_UP);
            BigDecimal ft2PerUnit = qty > 0 && !missing && !need.noneRequired()
                    ? lineTotalFt2.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP)
                    : null;
            BigDecimal lineUsedFt2;
            BigDecimal lineRemainingFt2;
            if (missing || need.noneRequired() || qty <= 0) {
                lineUsedFt2 = ZERO_FT2;
                lineRemainingFt2 = ZERO_FT2;
            } else {
                lineUsedFt2 = lineTotalFt2.multiply(BigDecimal.valueOf(used))
                        .divide(BigDecimal.valueOf(qty), 3, RoundingMode.HALF_UP);
                lineRemainingFt2 = lineTotalFt2.subtract(lineUsedFt2).max(BigDecimal.ZERO)
                        .setScale(3, RoundingMode.HALF_UP);
            }

            if (!missing && !need.noneRequired()) {
                remainingFt2 = remainingFt2.add(lineRemainingFt2);
                usedFt2Total = usedFt2Total.add(lineUsedFt2);
            }

            MaterialEntity material = null;
            BigDecimal available = null;
            if (need.materialId() != null) {
                material = materialsById.computeIfAbsent(need.materialId(),
                        id -> materialRepository.findById(id).orElse(null));
                available = availableByMaterial.computeIfAbsent(need.materialId(), id ->
                        leatherInventoryRepository.findByMaterialId(id)
                                .map(LeatherInventoryEntity::getQuantityAvailable)
                                .orElse(BigDecimal.ZERO));
            }

            String colorName = color != null ? color.getName() : (item.getColorId() != null ? "#" + item.getColorId() : "Sin color");
            String note = missing
                    ? need.reason()
                    : (need.noneRequired() ? "Sin cuero requerido" : null);

            lines.add(ProductionOrderLeatherEstimateResponse.LineEstimate.builder()
                    .itemId(item.getId())
                    .productId(item.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(item.getColorId())
                    .colorName(colorName)
                    .quantity(remaining)
                    .totalQuantity(qty)
                    .usedQuantity(used)
                    .ft2PerUnit(ft2PerUnit)
                    .lineFt2(lineRemainingFt2)
                    .totalFt2(lineTotalFt2)
                    .usedFt2(lineUsedFt2)
                    .leatherMaterialId(need.materialId())
                    .leatherMaterialSku(material != null ? material.getSku() : null)
                    .leatherMaterialName(material != null ? material.getName() : null)
                    .availableFt2(available)
                    .missingRecipe(missing)
                    .note(note)
                    .build());

            String colorKey = item.getColorId() != null ? "c:" + item.getColorId() : "none";
            ProductionOrderLeatherEstimateResponse.ColorSummary colorSum = byColor.computeIfAbsent(colorKey, k ->
                    ProductionOrderLeatherEstimateResponse.ColorSummary.builder()
                            .colorId(item.getColorId())
                            .colorName(colorName)
                            .quantity(0)
                            .totalQuantity(0)
                            .usedQuantity(0)
                            .totalFt2(ZERO_FT2)
                            .usedFt2(ZERO_FT2)
                            .lineCount(0)
                            .build());
            colorSum.setQuantity(colorSum.getQuantity() + remaining);
            colorSum.setTotalQuantity(colorSum.getTotalQuantity() + qty);
            colorSum.setUsedQuantity(colorSum.getUsedQuantity() + used);
            colorSum.setLineCount(colorSum.getLineCount() + 1);
            if (!missing && !need.noneRequired()) {
                colorSum.setTotalFt2(colorSum.getTotalFt2().add(lineRemainingFt2));
                colorSum.setUsedFt2(colorSum.getUsedFt2().add(lineUsedFt2));
            }

            if (need.materialId() != null && !missing && !need.noneRequired()) {
                MaterialEntity mat = material;
                BigDecimal avail = available;
                ProductionOrderLeatherEstimateResponse.MaterialSummary matSum = byMaterial.computeIfAbsent(
                        need.materialId(),
                        id -> ProductionOrderLeatherEstimateResponse.MaterialSummary.builder()
                                .leatherMaterialId(id)
                                .leatherMaterialSku(mat != null ? mat.getSku() : null)
                                .leatherMaterialName(mat != null ? mat.getName() : ("#" + id))
                                .totalFt2(ZERO_FT2)
                                .usedFt2(ZERO_FT2)
                                .availableFt2(avail)
                                .build());
                matSum.setTotalFt2(matSum.getTotalFt2().add(lineRemainingFt2));
                matSum.setUsedFt2(matSum.getUsedFt2().add(lineUsedFt2));
            }
        }

        List<ProductionOrderLeatherEstimateResponse.ColorSummary> colorSummaries = byColor.values().stream()
                .sorted(Comparator.comparing(
                        ProductionOrderLeatherEstimateResponse.ColorSummary::getColorName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        List<ProductionOrderLeatherEstimateResponse.MaterialSummary> materialSummaries = byMaterial.values().stream()
                .sorted(Comparator.comparing(
                        ProductionOrderLeatherEstimateResponse.MaterialSummary::getLeatherMaterialName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        return ProductionOrderLeatherEstimateResponse.builder()
                .productionOrderId(order.getId())
                .productionOrderCode(order.getCode())
                .orderType(order.getOrderType())
                .status(order.getStatus())
                .totalFt2(remainingFt2.setScale(3, RoundingMode.HALF_UP))
                .totalUnits(remainingUnits)
                .totalQuantity(totalUnits)
                .usedQuantity(usedUnits)
                .usedFt2(usedFt2Total.setScale(3, RoundingMode.HALF_UP))
                .linesMissingRecipe(missingRecipe)
                .lines(lines)
                .byColor(colorSummaries)
                .byMaterial(materialSummaries)
                .build();
    }
}
