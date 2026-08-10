package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KioscoStockProvisioningService {

    private final KioscoStockRepository kioscoStockRepository;
    private final KioscoMovementRepository kioscoMovementRepository;

    /**
     * Crea la fila de stock si no existe y la devuelve bloqueada para la operación.
     * Primero busca (evita inventar duplicados cuando color_id es NULL: en Postgres
     * UNIQUE no colisiona con NULL). Si aparecen filas duplicadas, las consolida.
     */
    @Transactional
    public KioscoStockEntity ensureStockRow(
            Long locationId,
            Long productId,
            Long colorId,
            Long userId,
            String hardwareCondition
    ) throws BusinessException {
        List<KioscoStockEntity> existing = kioscoStockRepository.findAllForUpdateByHardware(
                locationId, productId, colorId, hardwareCondition);
        if (!existing.isEmpty()) {
            return collapseDuplicates(existing);
        }

        kioscoStockRepository.insertIfAbsent(
                locationId, productId, colorId, userId, hardwareCondition);

        List<KioscoStockEntity> rows = kioscoStockRepository.findAllForUpdateByHardware(
                locationId, productId, colorId, hardwareCondition);
        if (rows.isEmpty()) {
            throw new BusinessException(
                    "No se pudo preparar el stock de kiosko para la operación.");
        }
        return collapseDuplicates(rows);
    }

    /**
     * Consolida filas duplicadas (p. ej. color_id NULL con UNIQUE clásico).
     * Fusiona {@code sizes_data} sumando por talla; {@code current_stock} queda
     * como suma de tallas si hay desglose, si no como suma de {@code current_stock}.
     */
    KioscoStockEntity collapseDuplicates(List<KioscoStockEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must not be empty");
        }
        if (rows.size() == 1) {
            return rows.get(0);
        }

        KioscoStockEntity keeper = rows.get(0);
        int totalStock = 0;
        int maxMinimum = 0;
        Map<String, BigDecimal> mergedSizes = new LinkedHashMap<>();
        boolean anySizes = false;

        for (KioscoStockEntity row : rows) {
            totalStock += safeInt(row.getCurrentStock());
            maxMinimum = Math.max(maxMinimum, safeInt(row.getMinimumStock()));
            Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(row.getSizesData());
            if (!sizes.isEmpty()) {
                anySizes = true;
                for (Map.Entry<String, BigDecimal> e : sizes.entrySet()) {
                    mergedSizes.merge(e.getKey(), e.getValue(), BigDecimal::add);
                }
            }
        }

        for (int i = 1; i < rows.size(); i++) {
            KioscoStockEntity duplicate = rows.get(i);
            kioscoMovementRepository.reassignKioscoStockId(duplicate.getId(), keeper.getId());
            kioscoStockRepository.delete(duplicate);
        }

        if (anySizes) {
            ProductInventorySizesJson.removeZeroEntries(mergedSizes);
            keeper.setSizesData(ProductInventorySizesJson.serialize(mergedSizes));
            keeper.setCurrentStock(
                    ProductInventorySizesJson.sum(mergedSizes)
                            .setScale(0, RoundingMode.HALF_UP)
                            .intValue());
        } else {
            keeper.setCurrentStock(totalStock);
        }
        keeper.setMinimumStock(maxMinimum);
        log.warn(
                "KIOSCO_STOCK_DUPLICATES_COLLAPSED locationId={} productId={} colorId={} hardware={} keptId={} mergedRows={} anySizes={}",
                keeper.getLocationId(),
                keeper.getProductId(),
                keeper.getColorId(),
                keeper.getHardwareCondition(),
                keeper.getId(),
                rows.size(),
                anySizes);
        return kioscoStockRepository.save(keeper);
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
