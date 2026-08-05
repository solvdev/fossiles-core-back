package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KioscoStockProvisioningService {

    private final KioscoStockRepository kioscoStockRepository;

    /**
     * Crea la fila de stock si no existe y la devuelve bloqueada para la operación.
     * INSERT ... ON CONFLICT evita abortar la transacción cuando dos movimientos
     * intentan preparar el mismo stock simultáneamente.
     */
    @Transactional
    public KioscoStockEntity ensureStockRow(
            Long locationId,
            Long productId,
            Long colorId,
            Long userId,
            String hardwareCondition
    ) throws BusinessException {
        kioscoStockRepository.insertIfAbsent(
                locationId, productId, colorId, userId, hardwareCondition);

        return kioscoStockRepository.findForUpdateByHardware(
                        locationId, productId, colorId, hardwareCondition)
                .orElseThrow(() -> new BusinessException(
                        "No se pudo preparar el stock de kiosko para la operación."));
    }
}
