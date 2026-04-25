package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "material_request_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequestItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_request_id", nullable = false)
    private Long materialRequestId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "quantity_requested", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityRequested;

    @Column(name = "uom_id")
    private Long uomId;

    @Column(name = "supplier_id")
    private Long supplierId; // Proveedor asignado para este item (opcional)
}

