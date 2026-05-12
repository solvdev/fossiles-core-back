package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Registro de salida de material (p.ej. desde kiosko) sin inventario destino — documento de trazabilidad.
 */
@Entity
@Table(name = "inventory_outflow")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryOutflowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_number", nullable = false, length = 40)
    private String ticketNumber;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "from_location_id", nullable = false)
    private Long fromLocationId;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(length = 500)
    private String reason;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reference_number", length = 80)
    private String referenceNumber;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
