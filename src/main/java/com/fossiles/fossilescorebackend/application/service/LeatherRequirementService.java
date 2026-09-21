package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductVariantLeatherRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Disponibilidad de cuero (ft²) vs receta {@code product_variant_leather}.
 * Reserva en memoria durante un plan para que dos líneas no peleen el mismo stock.
 */
@Service
@RequiredArgsConstructor
public class LeatherRequirementService {

    private final ProductVariantLeatherRepository variantLeatherRepository;
    private final LeatherInventoryRepository leatherInventoryRepository;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;

    public record LeatherNeed(Long materialId, BigDecimal qtyFt2, String reason) {
        public static LeatherNeed ok(Long materialId, BigDecimal qty) {
            return new LeatherNeed(materialId, qty, null);
        }

        public static LeatherNeed blocked(String reason) {
            return new LeatherNeed(null, BigDecimal.ZERO, reason);
        }

        public boolean blocked() {
            return reason != null;
        }

        public boolean noneRequired() {
            return reason == null && (materialId == null || qtyFt2 == null
                    || qtyFt2.compareTo(BigDecimal.ZERO) <= 0);
        }
    }

    public LeatherNeed resolveNeed(ProductEntity product, Long colorId, int quantity) {
        if (product == null || quantity <= 0) {
            return LeatherNeed.ok(null, BigDecimal.ZERO);
        }
        Optional<ProductVariantLeatherEntity> mapping = resolveMapping(product.getId(), colorId);
        if (mapping.isEmpty()) {
            return LeatherNeed.blocked("Sin receta de cuero (product_variant_leather) para "
                    + product.getCode());
        }
        ProductVariantLeatherEntity row = mapping.get();
        BigDecimal perUnit = row.getQtyPerUnit() != null ? row.getQtyPerUnit() : BigDecimal.ONE;
        BigDecimal needed = perUnit.multiply(BigDecimal.valueOf(quantity)).setScale(3, RoundingMode.HALF_UP);
        return LeatherNeed.ok(row.getLeatherMaterialId(), needed);
    }

    public Map<Long, BigDecimal> committedFt2ByMaterial() {
        Map<Long, BigDecimal> committed = new HashMap<>();
        List<TaskEntity> active = taskRepository.findPendingAndInProgressOrdered();
        for (TaskEntity task : active) {
            if (task == null || task.getId() == null) {
                continue;
            }
            if (Boolean.TRUE.equals(task.getLeatherDelivered())) {
                continue;
            }
            for (TaskItemEntity item : taskItemRepository.findByTaskId(task.getId())) {
                if (item == null || Boolean.TRUE.equals(item.getLeatherDelivered())) {
                    continue;
                }
                if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                    continue;
                }
                Optional<ProductVariantLeatherEntity> mapping =
                        resolveMapping(item.getProductId(), item.getColorId());
                if (mapping.isEmpty()) {
                    continue;
                }
                ProductVariantLeatherEntity row = mapping.get();
                BigDecimal perUnit = row.getQtyPerUnit() != null ? row.getQtyPerUnit() : BigDecimal.ONE;
                BigDecimal qty = perUnit.multiply(BigDecimal.valueOf(item.getQuantity()));
                committed.merge(row.getLeatherMaterialId(), qty, BigDecimal::add);
            }
        }
        return committed;
    }

    public boolean canCover(LeatherNeed need, Map<Long, BigDecimal> reservedThisRun) {
        if (need == null || need.blocked() || need.noneRequired()) {
            return !need.blocked();
        }
        BigDecimal available = leatherInventoryRepository.findByMaterialId(need.materialId())
                .map(LeatherInventoryEntity::getQuantityAvailable)
                .orElse(BigDecimal.ZERO);
        BigDecimal reserved = reservedThisRun.getOrDefault(need.materialId(), BigDecimal.ZERO);
        return available.subtract(reserved).compareTo(need.qtyFt2()) >= 0;
    }

    public String shortageMessage(LeatherNeed need, Map<Long, BigDecimal> reservedThisRun) {
        if (need.blocked()) {
            return need.reason();
        }
        BigDecimal available = leatherInventoryRepository.findByMaterialId(need.materialId())
                .map(LeatherInventoryEntity::getQuantityAvailable)
                .orElse(BigDecimal.ZERO);
        BigDecimal reserved = reservedThisRun.getOrDefault(need.materialId(), BigDecimal.ZERO);
        BigDecimal free = available.subtract(reserved);
        return "Cuero insuficiente: se necesitan " + need.qtyFt2()
                + " ft² y hay " + free.max(BigDecimal.ZERO) + " ft² disponibles.";
    }

    private Optional<ProductVariantLeatherEntity> resolveMapping(Long productId, Long colorId) {
        if (productId == null) {
            return Optional.empty();
        }
        if (colorId != null) {
            Optional<ProductVariantLeatherEntity> byColor =
                    variantLeatherRepository.findByProductIdAndColorId(productId, colorId);
            if (byColor.isPresent()) {
                return byColor;
            }
        }
        return variantLeatherRepository.findByProductIdAndColorIdIsNull(productId);
    }
}
