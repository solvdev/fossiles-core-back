package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryLocationTypeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ubicación de inventario usada para devoluciones de venta en línea (misma resolución que el despacho).
 */
@Component
@RequiredArgsConstructor
public class OnlineSaleReturnsWarehouseLocator {

    private static final List<String> RETURNS_WAREHOUSE_CODES = List.of(
            "BODEGA_DEVOLUCIONES",
            "BODEGA_DEV",
            "DEVOLUCION",
            "DEVOLUCIONES",
            "BODEGA_RET",
            "BODEGA_RETURN"
    );

    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final LocationRepository locationRepository;

    public Optional<LocationEntity> find() {
        for (String code : RETURNS_WAREHOUSE_CODES) {
            Optional<InventoryLocationTypeEntity> locType =
                    inventoryLocationTypeRepository.findByCodeAndIsActiveTrue(code);
            if (locType.isEmpty()) {
                continue;
            }
            LocationEntity loc = locationRepository.findAll().stream()
                    .filter(l -> code.equals(l.getCode()))
                    .findFirst()
                    .orElse(null);
            if (loc != null) {
                return Optional.of(loc);
            }
        }
        return Optional.empty();
    }

    public boolean matchesReturnsWarehouse(LocationEntity loc) {
        if (loc == null || loc.getCode() == null || loc.getCode().isBlank()) {
            return false;
        }
        String normalized = loc.getCode().trim().toUpperCase(Locale.ROOT);
        for (String rc : RETURNS_WAREHOUSE_CODES) {
            if (rc.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }
}
