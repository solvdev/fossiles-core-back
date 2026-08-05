package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    private KioscoStockEntity collapseDuplicates(List<KioscoStockEntity> rows) {
        if (rows.size() == 1) {
            return rows.get(0);
        }

        KioscoStockEntity keeper = rows.get(0);
        int totalStock = safeInt(keeper.getCurrentStock());
        int maxMinimum = safeInt(keeper.getMinimumStock());

        for (int i = 1; i < rows.size(); i++) {
            KioscoStockEntity duplicate = rows.get(i);
            totalStock += safeInt(duplicate.getCurrentStock());
            maxMinimum = Math.max(maxMinimum, safeInt(duplicate.getMinimumStock()));
            kioscoMovementRepository.reassignKioscoStockId(duplicate.getId(), keeper.getId());
            kioscoStockRepository.delete(duplicate);
        }

        keeper.setCurrentStock(totalStock);
        keeper.setMinimumStock(maxMinimum);
        log.warn(
                "KIOSCO_STOCK_DUPLICATES_COLLAPSED locationId={} productId={} colorId={} hardware={} keptId={} mergedRows={}",
                keeper.getLocationId(),
                keeper.getProductId(),
                keeper.getColorId(),
                keeper.getHardwareCondition(),
                keeper.getId(),
                rows.size());
        return kioscoStockRepository.save(keeper);
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
