package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lector/asignador de ENVP-nnnnn desde el correlativo LF_ENVP.
 * El número se reserva al crear el envío, no al crear la orden.
 */
@Service
@RequiredArgsConstructor
public class OpvVendorShipmentNumberService {

    public static final String SHIPMENT_PREFIX = "ENVP";

    private final ShipmentNumberCounterService shipmentNumberCounterService;

    /** Asigna ENVP-nnnnn si la orden aún no tiene número. No pisa un valor existente. */
    public void assignIfMissing(ProductionOrderEntity order) throws BusinessException {
        if (order.getVendorShipmentNumber() != null && !order.getVendorShipmentNumber().isBlank()) {
            return;
        }
        order.setVendorShipmentNumber(nextNumber());
    }

    public String nextNumber() throws BusinessException {
        return shipmentNumberCounterService.nextEnvp();
    }
}
