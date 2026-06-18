package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;

/**
 * Correlativo de envío para órdenes OPV vendedor (Luis Felipe), paralelo a ENVL de venta en línea.
 */
@Service
@RequiredArgsConstructor
public class OpvVendorShipmentNumberService {

    public static final String SHIPMENT_PREFIX = "ENVP";
    private static final Pattern SHIPMENT_PATTERN = Pattern.compile("^ENVP-(\\d+)$");

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductShipmentRepository productShipmentRepository;

    /** Asigna ENVP-nnnnn si la orden aún no tiene número de envío OPV. */
    public void assignIfMissing(ProductionOrderEntity order) {
        if (order.getVendorShipmentNumber() != null && !order.getVendorShipmentNumber().isBlank()) {
            return;
        }
        order.setVendorShipmentNumber(nextFreeNumber());
    }

    /**
     * Si el ENVP de la orden ya está usado en product_shipment de otra OP, asigna el siguiente libre.
     */
    public boolean reconcileVendorNumberIfColliding(ProductionOrderEntity order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        String vendor = order.getVendorShipmentNumber();
        if (vendor == null || vendor.isBlank() || !SHIPMENT_PATTERN.matcher(vendor.trim()).matches()) {
            return false;
        }
        if (!productShipmentRepository.existsByShipmentNumber(vendor.trim())) {
            return false;
        }
        Optional<ProductShipmentEntity> existing = productShipmentRepository.findByShipmentNumber(vendor.trim());
        if (existing.isPresent()) {
            Long ownerId = existing.get().getProductionOrderId();
            if (ownerId != null && ownerId.equals(order.getId())) {
                return false;
            }
        }
        order.setVendorShipmentNumber(nextFreeNumber());
        return true;
    }

    public String nextNumber() {
        return nextFreeNumber();
    }

    private String nextFreeNumber() {
        List<String> all = new ArrayList<>();
        all.addAll(productionOrderRepository.findAllVendorShipmentNumbers());
        all.addAll(productShipmentRepository.findEnvpPrefixShipmentNumbers());
        int maxSeq = all.stream()
                .map(SHIPMENT_PATTERN::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max()
                .orElse(0);
        for (int seq = maxSeq + 1; seq < maxSeq + 50000; seq++) {
            String candidate = String.format("%s-%05d", SHIPMENT_PREFIX, seq);
            if (!isEnvpTaken(candidate, null)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No hay números ENVP disponibles");
    }

    private boolean isEnvpTaken(String envp, Long excludeOrderId) {
        if (productShipmentRepository.existsByShipmentNumber(envp)) {
            return true;
        }
        if (excludeOrderId == null) {
            return productionOrderRepository.existsByVendorShipmentNumber(envp);
        }
        return productionOrderRepository.existsByVendorShipmentNumberAndIdNot(envp, excludeOrderId);
    }
}
