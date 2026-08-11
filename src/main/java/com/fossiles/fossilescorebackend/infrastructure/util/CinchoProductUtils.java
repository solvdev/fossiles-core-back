package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Productos de mesa cinchos: cinchos FOSS / nombre con "cincho", o pulsera(s) por nombre.
 */
public final class CinchoProductUtils {

    public static final String WORK_STATUS_PENDING = "PENDING";
    public static final String WORK_STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String WORK_STATUS_COMPLETED = "COMPLETED";

    private CinchoProductUtils() {}

    /** Cincho clásico (FOSS o nombre con cincho). Empaques SUM- quedan excluidos aunque el nombre diga "cincho". */
    public static boolean isFossCinchoProduct(ProductEntity product) {
        if (product == null) {
            return false;
        }
        if (ProductCinchoType.isPackagingProductCode(product.getCode())) {
            return false;
        }
        if (isFossCinchoCode(product.getCode())) {
            return true;
        }
        return nameIndicatesCincho(product.getName());
    }

    /** Mesa cinchos del día: cinchos + pulseras. */
    public static boolean isMesaCinchosProduct(ProductEntity product) {
        if (product == null) {
            return false;
        }
        return isFossCinchoProduct(product) || nameIndicatesPulsera(product.getName());
    }

    /**
     * Cincho para ENRUTAR PRODUCCIÓN (mesa cinchos vs. tarea/mesa normal): solo señal
     * explícita ({@code cinchoType}, marcada a mano en el producto) o nombre. A diferencia
     * de {@link #isFossCinchoProduct}, NO usa el prefijo de código "FOSS" — ese prefijo es
     * de marca/catálogo general y no implica por sí solo que el producto sea un cincho,
     * lo que hacía que productos regulares con código FOSS-... quedaran atrapados fuera
     * del Organizador de Tareas. Usar solo para decidir a qué tablero va una línea de OP;
     * NO reemplaza {@link #isFossCinchoProduct} en la lógica de inventario/tallas.
     */
    public static boolean isCinchoLineForProduction(ProductEntity product) {
        if (product == null) {
            return false;
        }
        // Empaques SUM- (p.ej. "BOLSA PARA CINCHOS") no son cinchos aunque el nombre diga cincho.
        if (ProductCinchoType.isPackagingProductCode(product.getCode())) {
            return false;
        }
        String cinchoType = product.getCinchoType();
        if (cinchoType != null && !cinchoType.isBlank()) {
            return true;
        }
        return nameIndicatesCincho(product.getName());
    }

    /** Línea de mesa cinchos para producción: cincho (arriba) o pulsera por nombre. */
    public static boolean isMesaCinchosLineForProduction(ProductEntity product) {
        if (product == null) {
            return false;
        }
        return isCinchoLineForProduction(product) || nameIndicatesPulsera(product.getName());
    }

    public static boolean isFossCinchoCode(String code) {
        if (code == null) {
            return false;
        }
        return code.toUpperCase(Locale.ROOT).startsWith("FOSS");
    }

    private static boolean nameIndicatesCincho(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = normalizeName(name);
        return n.contains("cincho");
    }

    public static boolean nameIndicatesPulsera(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return normalizeName(name).contains("pulsera");
    }

    private static String normalizeName(String name) {
        return Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
}
