package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correlativo de envío para órdenes OPV vendedor (Luis Felipe), paralelo a ENVL de venta en línea.
 */
@Service
@RequiredArgsConstructor
public class OpvVendorShipmentNumberService {

    public static final String SHIPMENT_PREFIX = "ENVP";
    private static final Pattern SHIPMENT_PATTERN = Pattern.compile("^ENVP-(\\d+)$");

    private final ProductionOrderRepository productionOrderRepository;

    /** Asigna ENVP-nnnnn si la orden aún no tiene número de envío OPV. */
    public void assignIfMissing(ProductionOrderEntity order) {
        if (order.getVendorShipmentNumber() != null && !order.getVendorShipmentNumber().isBlank()) {
            return;
        }
        order.setVendorShipmentNumber(nextNumber());
    }

    public String nextNumber() {
        int maxSeq = productionOrderRepository.findAllVendorShipmentNumbers().stream()
                .map(SHIPMENT_PATTERN::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max()
                .orElse(0);
        return String.format("%s-%05d", SHIPMENT_PREFIX, maxSeq + 1);
    }
}
