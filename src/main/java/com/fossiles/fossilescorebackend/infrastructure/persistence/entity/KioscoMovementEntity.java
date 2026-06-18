package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "kiosco_movement")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoMovementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kiosco_stock_id", nullable = false)
    private Long kioscoStockId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 40)
    private KioscoMovementType movementType;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "stock_before", nullable = false)
    private Integer stockBefore;

    @Column(name = "stock_after", nullable = false)
    private Integer stockAfter;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "affects_stock", nullable = false)
    @Builder.Default
    private Boolean affectsStock = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "origin_location_id")
    private Long originLocationId;

    @Column(name = "destination_location_id")
    private Long destinationLocationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kiosco_stock_id", insertable = false, updatable = false)
    private KioscoStockEntity kioscoStock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private UserEntity user;

    @PrePersist
    protected void onCreate() {
        if (affectsStock == null) {
            affectsStock = true;
        }
        createdAt = LocalDateTime.now();
    }
}
