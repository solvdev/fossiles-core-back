package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductVariantLeatherEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductVariantLeatherRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Receta de cuero por (producto, color): la fila de {@code product_variant_leather} del color, la genérica del
 * producto o, si no hay ninguna, la derivada de otro color del producto con el cuero del color de la línea.
 *
 * <p>Antes cada combinación eran de 1 a 6 consultas (fila del color, genérica, todas las del producto, color,
 * cuero por nombre, cuero reversible). El Dashboard de cuero resuelve unas 540 combinaciones y Armar el día otras
 * tantas. Ahora las recetas, los colores y los materiales se leen juntos (3 consultas) y quedan en la caché
 * {@link CacheConfig#LEATHER_RECIPES_CACHE}; cada combinación se resuelve en memoria una sola vez.
 *
 * <p>Se invalida al escribir recetas, materiales o colores ({@code ProductionCenterCacheInvalidator}); el TTL
 * de 10 minutos cubre lo que entre por otro lado (materiales creados desde compras).
 */
@Component
public class LeatherRecipeCatalog {

    static final String REVERSIBLE_LEATHER_NAME = "CUERO DE CINCHO REVERSIBLE";
    /** Color de la línea → nombre que usa el catálogo de cuero para ese color. */
    private static final Map<String, String> LEATHER_COLOR_ALIASES = Map.of(
            "ROSADO", "ROSA PRINCESA",
            "ROSA GOLD", "GOLD ROSE");
    private static final String SNAPSHOT_KEY = "all";

    /** Cuero y cantidad por pieza de una receta (guardada o derivada). */
    public record LeatherRecipe(Long leatherMaterialId, BigDecimal qtyPerUnit) {}

    private record Key(Long productId, Long colorId) {}

    private record Row(Long colorId, Long leatherMaterialId, BigDecimal qtyPerUnit) {}

    private record Mat(Long id, String name, String status, Long materialColorId) {}

    /** Foto de recetas, colores y materiales; las combinaciones ya resueltas se guardan aquí mismo. */
    static final class Snapshot {
        private final Map<Long, List<Row>> rowsByProduct;
        private final Map<Long, String> colorNames;
        private final List<Mat> materials;
        private final Map<Long, Mat> materialsById;
        private final Map<Key, Optional<LeatherRecipe>> resolved = new ConcurrentHashMap<>();

        private Snapshot(Map<Long, List<Row>> rowsByProduct, Map<Long, String> colorNames, List<Mat> materials) {
            this.rowsByProduct = rowsByProduct;
            this.colorNames = colorNames;
            this.materials = materials;
            this.materialsById = materials.stream().collect(Collectors.toMap(Mat::id, m -> m, (a, b) -> a));
        }
    }

    private final ProductVariantLeatherRepository variantLeatherRepository;
    private final ColorRepository colorRepository;
    private final MaterialRepository materialRepository;
    private final CacheManager cacheManager;

    public LeatherRecipeCatalog(ProductVariantLeatherRepository variantLeatherRepository,
                                ColorRepository colorRepository,
                                MaterialRepository materialRepository,
                                CacheManager cacheManager) {
        this.variantLeatherRepository = variantLeatherRepository;
        this.colorRepository = colorRepository;
        this.materialRepository = materialRepository;
        this.cacheManager = cacheManager;
    }

    /** Receta de (producto, color); vacía si no hay ni se puede derivar. */
    public Optional<LeatherRecipe> resolve(Long productId, Long colorId) {
        if (productId == null) {
            return Optional.empty();
        }
        Snapshot snapshot = snapshot();
        return snapshot.resolved.computeIfAbsent(new Key(productId, colorId), k -> compute(snapshot, k));
    }

    public void evict() {
        Cache cache = cacheManager.getCache(CacheConfig.LEATHER_RECIPES_CACHE);
        if (cache != null) {
            cache.clear();
        }
    }

    private Snapshot snapshot() {
        Cache cache = cacheManager.getCache(CacheConfig.LEATHER_RECIPES_CACHE);
        if (cache == null) {
            return load();
        }
        try {
            return cache.get(SNAPSHOT_KEY, this::load);
        } catch (Cache.ValueRetrievalException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    private Snapshot load() {
        Map<Long, List<Row>> rowsByProduct = variantLeatherRepository.findAll().stream()
                .filter(r -> r.getProductId() != null)
                .sorted(Comparator.comparing(ProductVariantLeatherEntity::getColorId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.groupingBy(ProductVariantLeatherEntity::getProductId,
                        Collectors.mapping(r -> new Row(r.getColorId(), r.getLeatherMaterialId(), r.getQtyPerUnit()),
                                Collectors.toList())));
        Map<Long, String> colorNames = new HashMap<>();
        for (ColorEntity c : colorRepository.findAll()) {
            if (c.getId() != null && c.getName() != null) {
                colorNames.put(c.getId(), c.getName());
            }
        }
        List<Mat> materials = materialRepository.findAll().stream()
                .filter(m -> m.getId() != null)
                .sorted(Comparator.comparing(MaterialEntity::getId))
                .map(m -> new Mat(m.getId(), m.getName(), m.getStatus(), m.getMaterialColorId()))
                .toList();
        return new Snapshot(rowsByProduct, colorNames, materials);
    }

    // ---- Resolución (misma regla que tenía LeatherRequirementService, ahora sobre la foto) ----

    private static Optional<LeatherRecipe> compute(Snapshot s, Key key) {
        List<Row> rows = s.rowsByProduct.getOrDefault(key.productId(), List.of());
        if (key.colorId() != null) {
            Optional<Row> byColor = rows.stream().filter(r -> key.colorId().equals(r.colorId())).findFirst();
            if (byColor.isPresent()) {
                return byColor.map(LeatherRecipeCatalog::toRecipe);
            }
        }
        Optional<Row> generic = rows.stream().filter(r -> r.colorId() == null).findFirst();
        if (generic.isPresent() || key.colorId() == null) {
            return generic.map(LeatherRecipeCatalog::toRecipe);
        }
        return deriveFromOtherColor(s, rows, key.colorId());
    }

    private static LeatherRecipe toRecipe(Row row) {
        return new LeatherRecipe(row.leatherMaterialId(), row.qtyPerUnit());
    }

    /**
     * El producto tiene receta en otros colores pero no en este: el cuero es del color de la línea y la cantidad
     * por pieza es la que ya usa el producto. Colores combinados (X/Y) o sin cuero equivalente en el catálogo
     * siguen sin receta.
     */
    private static Optional<LeatherRecipe> deriveFromOtherColor(Snapshot s, List<Row> productRows, Long colorId) {
        List<Row> recipes = productRows.stream()
                .filter(r -> r.colorId() != null && r.qtyPerUnit() != null)
                .toList();
        if (recipes.isEmpty()) {
            return Optional.empty();
        }
        String colorName = s.colorNames.get(colorId);
        if (colorName == null) {
            return Optional.empty();
        }
        Optional<Mat> leather = leatherMaterialForColor(s, colorName);
        if (leather.isEmpty()) {
            return Optional.empty();
        }
        Long materialId = leather.get().id();
        if (isPlainCafeOrNegro(colorName) && productUsesReversible(s, recipes)
                && leather.get().materialColorId() != null) {
            Long leatherColor = leather.get().materialColorId();
            materialId = s.materials.stream()
                    .filter(m -> sameNameIgnoreCase(m.name(), REVERSIBLE_LEATHER_NAME)
                            && leatherColor.equals(m.materialColorId()))
                    .findFirst()
                    .map(Mat::id)
                    .orElse(materialId);
        }
        return Optional.of(new LeatherRecipe(materialId, mostCommonQty(recipes)));
    }

    /** La cantidad más repetida entre los colores del producto; si hay empate, la mayor. */
    private static BigDecimal mostCommonQty(List<Row> recipes) {
        Map<BigDecimal, Integer> counts = new HashMap<>();
        for (Row r : recipes) {
            counts.merge(r.qtyPerUnit().stripTrailingZeros(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<BigDecimal, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(BigDecimal.ONE);
    }

    /** El producto ya usa cuero de cincho reversible en CAFE o NEGRO (color 2 y 3). */
    private static boolean productUsesReversible(Snapshot s, List<Row> recipes) {
        return recipes.stream()
                .filter(r -> r.colorId() != null && (r.colorId() == 2L || r.colorId() == 3L))
                .map(Row::leatherMaterialId)
                .filter(Objects::nonNull)
                .map(s.materialsById::get)
                .filter(Objects::nonNull)
                .anyMatch(m -> REVERSIBLE_LEATHER_NAME.equalsIgnoreCase(m.name()));
    }

    private static boolean isPlainCafeOrNegro(String colorName) {
        String n = normalize(colorName);
        return "CAFE".equals(n) || "NEGRO".equals(n);
    }

    /** «CUERO color acabado»; si no existe con acabado, el liso del color base. */
    private static Optional<Mat> leatherMaterialForColor(Snapshot s, String colorName) {
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
        Optional<Mat> exact = activeMaterial(s, "CUERO " + base + " " + finish);
        if (exact.isPresent() || "LISO".equals(finish)) {
            return exact;
        }
        return activeMaterial(s, "CUERO " + base + " LISO");
    }

    private static Optional<Mat> activeMaterial(Snapshot s, String name) {
        return s.materials.stream()
                .filter(m -> sameNameIgnoreCase(m.name(), name))
                .filter(m -> m.status() == null || "active".equalsIgnoreCase(m.status()))
                .findFirst();
    }

    /** Igual que {@code findByNameIgnoreCase}: {@code upper(name) = upper(?)}. */
    private static boolean sameNameIgnoreCase(String a, String b) {
        return a != null && b != null && a.toUpperCase(Locale.ROOT).equals(b.toUpperCase(Locale.ROOT));
    }

    /** Mayúsculas y sin tildes en vocales (la Ñ se conserva: el catálogo tiene COÑAC). */
    private static String normalize(String s) {
        return s.trim().toUpperCase(Locale.ROOT)
                .replace("Á", "A").replace("É", "E").replace("Í", "I")
                .replace("Ó", "O").replace("Ú", "U").replace("Ü", "U");
    }
}
