package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialInventory;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Valor del stock de materiales por proveedor (Reportes de compras > Valor de materiales):
 * existencias de {@code material_inventory} × costo unitario del material (igual que "Inventario actual").
 * Se agrupa en "La Bandola de Oro" y "Otros"; los materiales cuyo nombre dice "cuero" no se cuentan.
 */
@Service
@RequiredArgsConstructor
public class MaterialStockValueService {

    public static final String GROUP_BANDOLA = "La Bandola de Oro";
    public static final String GROUP_OTHERS = "Otros";

    private final MaterialRepository materialRepository;
    private final MaterialInventoryRepository materialInventoryRepository;
    private final SupplierRepository supplierRepository;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Report {
        private LocalDateTime generatedAt;
        private List<Group> groups;
        private BigDecimal totalValue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Group {
        private String name;
        /** Materiales con existencia que suman al grupo. */
        private int materials;
        private BigDecimal value;
    }

    /** Material con su existencia, costo y proveedor, listo para agrupar. */
    record Line(String materialName, String supplierName, BigDecimal quantity, BigDecimal unitCost) {
    }

    @Transactional(readOnly = true)
    public Report getValueBySupplier() {
        Map<Long, BigDecimal> qtyByMaterial = new HashMap<>();
        for (MaterialInventory inv : materialInventoryRepository.findAll()) {
            if (inv.getMaterialId() != null && inv.getQuantity() != null) {
                qtyByMaterial.merge(inv.getMaterialId(), inv.getQuantity(), BigDecimal::add);
            }
        }
        Map<Long, String> supplierNames = supplierRepository.findAll().stream()
                .filter(s -> s.getName() != null)
                .collect(Collectors.toMap(SupplierEntity::getId, SupplierEntity::getName, (a, b) -> a));
        List<Line> lines = new ArrayList<>();
        for (MaterialEntity m : materialRepository.findAll()) {
            lines.add(new Line(m.getName(), m.getSupplierId() == null ? null : supplierNames.get(m.getSupplierId()),
                    qtyByMaterial.get(m.getId()), m.getUnitCost()));
        }
        return group(lines);
    }

    static Report group(List<Line> lines) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        values.put(GROUP_BANDOLA, BigDecimal.ZERO);
        values.put(GROUP_OTHERS, BigDecimal.ZERO);
        for (Line l : lines) {
            if (isLeather(l.materialName())) {
                continue;
            }
            BigDecimal qty = l.quantity() == null ? BigDecimal.ZERO : l.quantity();
            BigDecimal cost = l.unitCost() == null ? BigDecimal.ZERO : l.unitCost();
            if (qty.signum() <= 0 || cost.signum() <= 0) {
                continue;
            }
            String group = isBandola(l.supplierName()) ? GROUP_BANDOLA : GROUP_OTHERS;
            values.merge(group, qty.multiply(cost), BigDecimal::add);
            counts.merge(group, 1, Integer::sum);
        }
        List<Group> groups = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : values.entrySet()) {
            BigDecimal v = e.getValue().setScale(2, RoundingMode.HALF_UP);
            // Grupo sin valor: no aparece
            if (v.signum() <= 0) {
                continue;
            }
            groups.add(Group.builder().name(e.getKey()).materials(counts.getOrDefault(e.getKey(), 0)).value(v).build());
            total = total.add(v);
        }
        return Report.builder().generatedAt(GuatemalaDateTime.now()).groups(groups).totalValue(total).build();
    }

    static boolean isLeather(String materialName) {
        return normalize(materialName).contains("cuero");
    }

    static boolean isBandola(String supplierName) {
        return normalize(supplierName).contains("bandola");
    }

    /** Minúsculas y sin tildes. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
