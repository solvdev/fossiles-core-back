package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductVariantLeatherRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final ColorRepository colorRepository;
    private final MaterialRepository materialRepository;

    private static final String REVERSIBLE_LEATHER_NAME = "CUERO DE CINCHO REVERSIBLE";
    /** Color de la línea → nombre que usa el catálogo de cuero para ese color. */
    private static final Map<String, String> LEATHER_COLOR_ALIASES = Map.of(
            "ROSADO", "ROSA PRINCESA",
            "ROSA GOLD", "GOLD ROSE");

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
        Optional<ProductVariantLeatherEntity> generic =
                variantLeatherRepository.findByProductIdAndColorIdIsNull(productId);
        if (generic.isPresent() || colorId == null) {
            return generic;
        }
        return deriveFromOtherColor(productId, colorId);
    }

    /**
     * El producto tiene receta en otros colores pero no en este: el cuero es del color de la
     * línea y la cantidad por pieza es la que ya usa el producto. No se guarda nada; es una
     * receta calculada. Colores combinados (X/Y) o sin cuero equivalente en el catálogo siguen
     * sin receta, como antes.
     */
    private Optional<ProductVariantLeatherEntity> deriveFromOtherColor(Long productId, Long colorId) {
        List<ProductVariantLeatherEntity> recipes = variantLeatherRepository.findByProductIdOrderByColorIdAsc(productId)
                .stream()
                .filter(r -> r.getColorId() != null && r.getQtyPerUnit() != null)
                .toList();
        if (recipes.isEmpty()) {
            return Optional.empty();
        }
        Optional<ColorEntity> color = colorRepository.findById(colorId);
        if (color.isEmpty() || color.get().getName() == null) {
            return Optional.empty();
        }
        Optional<MaterialEntity> leather = leatherMaterialForColor(color.get().getName());
        if (leather.isEmpty()) {
            return Optional.empty();
        }
        Long materialId = leather.get().getId();
        if (isPlainCafeOrNegro(color.get().getName()) && productUsesReversible(recipes)
                && leather.get().getMaterialColorId() != null) {
            materialId = materialRepository
                    .findByNameIgnoreCaseAndMaterialColorId(REVERSIBLE_LEATHER_NAME, leather.get().getMaterialColorId())
                    .stream().findFirst().map(MaterialEntity::getId).orElse(materialId);
        }
        return Optional.of(ProductVariantLeatherEntity.builder()
                .productId(productId)
                .colorId(colorId)
                .leatherMaterialId(materialId)
                .qtyPerUnit(mostCommonQty(recipes))
                .build());
    }

    /** La cantidad más repetida entre los colores del producto; si hay empate, la mayor. */
    private static BigDecimal mostCommonQty(List<ProductVariantLeatherEntity> recipes) {
        Map<BigDecimal, Integer> counts = new HashMap<>();
        for (ProductVariantLeatherEntity r : recipes) {
            counts.merge(r.getQtyPerUnit().stripTrailingZeros(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<BigDecimal, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(BigDecimal.ONE);
    }

    /** El producto ya usa cuero de cincho reversible en CAFE o NEGRO (color 2 y 3). */
    private boolean productUsesReversible(List<ProductVariantLeatherEntity> recipes) {
        List<Long> cafeNegro = recipes.stream()
                .filter(r -> r.getColorId() != null && (r.getColorId() == 2L || r.getColorId() == 3L))
                .map(ProductVariantLeatherEntity::getLeatherMaterialId)
                .toList();
        return !cafeNegro.isEmpty() && materialRepository.findAllById(cafeNegro).stream()
                .anyMatch(m -> REVERSIBLE_LEATHER_NAME.equalsIgnoreCase(m.getName()));
    }

    private static boolean isPlainCafeOrNegro(String colorName) {
        String n = normalize(colorName);
        return "CAFE".equals(n) || "NEGRO".equals(n);
    }

    /** «CUERO color acabado»; si no existe con acabado, el liso del color base. */
    private Optional<MaterialEntity> leatherMaterialForColor(String colorName) {
        String n = normalize(colorName);
        if (n.contains("/")) {
            return Optional.empty();
        }
        String finish = n.contains("FLORES") ? "FLORES" : n.contains("ESPARTACO") ? "ESPARTACO" : "LISO";
        String base = n.replace("ACABADO", "").replace("FLORES", "").replace("ESPARTACO", "")
                .replaceAll("\\s+", " ").trim();
        base = LEATHER_COLOR_ALIASES.getOrDefault(base, base);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        Optional<MaterialEntity> exact = activeMaterial("CUERO " + base + " " + finish);
        if (exact.isPresent() || "LISO".equals(finish)) {
            return exact;
        }
        return activeMaterial("CUERO " + base + " LISO");
    }

    private Optional<MaterialEntity> activeMaterial(String name) {
        return materialRepository.findByNameIgnoreCase(name).stream()
                .filter(m -> m.getStatus() == null || "active".equalsIgnoreCase(m.getStatus()))
                .findFirst();
    }

    /** Mayúsculas y sin tildes en vocales (la Ñ se conserva: el catálogo tiene COÑAC). */
    private static String normalize(String s) {
        return s.trim().toUpperCase(Locale.ROOT)
                .replace("Á", "A").replace("É", "E").replace("Í", "I")
                .replace("Ó", "O").replace("Ú", "U").replace("Ü", "U");
    }
}
