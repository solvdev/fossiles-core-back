package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Registra las compensaciones/transferencias de saldo entre compras.
 * Cuando una compra tiene sobrante (vuelto), ese saldo puede compensar
 * el faltante de otra compra, manteniendo la caja chica cuadrada.
 */
@Entity
@Table(name = "purchase_compensation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseCompensationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Compra que APORTA el sobrante (origen del dinero) */
    @Column(name = "source_purchase_id", nullable = false)
    private Long sourcePurchaseId;

    /** Compra que RECIBE la compensación (destino del dinero) */
    @Column(name = "target_purchase_id", nullable = false)
    private Long targetPurchaseId;

    /** Monto de la compensación */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Descripción/motivo de la compensación */
    @Column(length = 500)
    private String description;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

