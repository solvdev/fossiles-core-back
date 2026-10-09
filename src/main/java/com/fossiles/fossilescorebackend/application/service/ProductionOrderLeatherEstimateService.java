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
 * Cuero requerido por una OP: ft² según receta {@code product_variant_leather}
 * del color de cada línea (cantidad × pies por unidad).
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderLeatherEstimateService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
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

        Map<Long, MaterialEntity> materialsById = new HashMap<>();
        Map<Long, BigDecimal> availableByMaterial = new HashMap<>();

        List<ProductionOrderLeatherEstimateResponse.LineEstimate> lines = new ArrayList<>();
        BigDecimal totalFt2 = BigDecimal.ZERO;
        int totalUnits = 0;
        int missingRecipe = 0;

        Map<String, ProductionOrderLeatherEstimateResponse.ColorSummary> byColor = new LinkedHashMap<>();
        Map<Long, ProductionOrderLeatherEstimateResponse.MaterialSummary> byMaterial = new LinkedHashMap<>();

        for (ProductionOrderItemEntity item : items) {
            ProductEntity product = item.getProductId() != null ? productsById.get(item.getProductId()) : null;
            ColorEntity color = item.getColorId() != null ? colorsById.get(item.getColorId()) : null;
            int qty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            totalUnits += qty;

            LeatherRequirementService.LeatherNeed need =
                    leatherRequirementService.resolveNeed(product, item.getColorId(), qty);

            boolean missing = need.blocked();
            if (missing) {
                missingRecipe++;
            }

            BigDecimal lineFt2 = missing || need.noneRequired()
                    ? BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)
                    : need.qtyFt2().setScale(3, RoundingMode.HALF_UP);
            BigDecimal ft2PerUnit = qty > 0 && !missing && !need.noneRequired()
                    ? lineFt2.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP)
                    : null;

            if (!missing && !need.noneRequired()) {
                totalFt2 = totalFt2.add(lineFt2);
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
                    .quantity(qty)
                    .ft2PerUnit(ft2PerUnit)
                    .lineFt2(lineFt2)
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
                            .totalFt2(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP))
                            .lineCount(0)
                            .build());
            colorSum.setQuantity(colorSum.getQuantity() + qty);
            colorSum.setLineCount(colorSum.getLineCount() + 1);
            if (!missing && !need.noneRequired()) {
                colorSum.setTotalFt2(colorSum.getTotalFt2().add(lineFt2));
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
                                .totalFt2(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP))
                                .availableFt2(avail)
                                .build());
                matSum.setTotalFt2(matSum.getTotalFt2().add(lineFt2));
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
                .totalFt2(totalFt2.setScale(3, RoundingMode.HALF_UP))
                .totalUnits(totalUnits)
                .linesMissingRecipe(missingRecipe)
                .lines(lines)
                .byColor(colorSummaries)
                .byMaterial(materialSummaries)
                .build();
    }
}
