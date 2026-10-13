package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Distingue producto terminado de empaque en ventas. Empaque = código de producto que empieza con SUM
 * ({@link ProductCinchoType#isPackagingProductCode}); un ítem sin código ni producto resoluble se
 * considera producto terminado.
 */
public final class FinishedProductClassifier {

    private static final String SIZE_SUFFIX_MARKER = " T.";

    private FinishedProductClassifier() {
    }

    public static boolean isPackaging(String productCode) {
        return ProductCinchoType.isPackagingProductCode(productCode);
    }

    /**
     * @param productCode        código guardado en el ítem (kiosko / online); puede venir vacío
     * @param productId          id de producto del ítem
     * @param codeByProductId    código de catálogo por id, usado cuando el ítem no trae código
     */
    public static boolean isPackaging(String productCode, Long productId, Map<Long, String> codeByProductId) {
        if (productCode != null && !productCode.isBlank()) {
            return isPackaging(productCode);
        }
        if (productId != null && codeByProductId != null) {
            return isPackaging(codeByProductId.get(productId));
        }
        return false;
    }

    public static boolean isFinished(String productCode, Long productId, Map<Long, String> codeByProductId) {
        return !isPackaging(productCode, productId, codeByProductId);
    }

    /** Ítem de orden vendedor: el código sale del catálogo por productId. */
    public static boolean isPackaging(Long productId, Map<Long, ProductEntity> productsById) {
        if (productId == null || productsById == null) {
            return false;
        }
        ProductEntity product = productsById.get(productId);
        return product != null && isPackaging(product.getCode());
    }

    public static Map<Long, String> codesById(Collection<ProductEntity> products) {
        Map<Long, String> codes = new HashMap<>();
        for (ProductEntity product : products) {
            if (product.getId() != null) {
                codes.put(product.getId(), product.getCode());
            }
        }
        return codes;
    }

    /** Nombre del producto base: quita el sufijo de talla " T.xx" que agrega el POS al nombre del ítem. */
    public static String baseProductName(String productName) {
        if (productName == null) {
            return null;
        }
        int idx = productName.lastIndexOf(SIZE_SUFFIX_MARKER);
        String base = idx < 0 ? productName : productName.substring(0, idx);
        return base.trim();
    }
}
