package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correlativo de documento de envío para órdenes OPI (INTERNA): ENVI-nnnnn.
 * Secuencia global compartida con números ya guardados en envíos de producto (product_shipment).
 */
@Service
@RequiredArgsConstructor
public class OpiVendorShipmentNumberService {

    public static final String SHIPMENT_PREFIX = "ENVI";
    private static final Pattern SHIPMENT_PATTERN = Pattern.compile("^ENVI-(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductShipmentRepository productShipmentRepository;

    public void assignIfMissing(ProductionOrderEntity order) {
        if (order.getVendorShipmentNumber() != null && !order.getVendorShipmentNumber().isBlank()) {
            return;
        }
        order.setVendorShipmentNumber(nextNumber());
    }

    public String nextNumber() {
        int maxSeq = maxSequenceFromList(productionOrderRepository.findAllVendorShipmentNumbers());
        maxSeq = Math.max(maxSeq, maxSequenceFromList(productShipmentRepository.findEnviPrefixShipmentNumbers()));
        return String.format("%s-%05d", SHIPMENT_PREFIX, maxSeq + 1);
    }

    private static int maxSequenceFromList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream()
                .map(SHIPMENT_PATTERN::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max()
                .orElse(0);
    }
}
