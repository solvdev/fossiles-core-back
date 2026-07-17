package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiosk_exchange_slip")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangeSlipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slip_number", length = 60, nullable = false, unique = true)
    private String slipNumber;

    @Column(name = "slip_type", length = 20, nullable = false)
    @Builder.Default
    private String slipType = "EXCHANGE";

    @Column(name = "kiosk_location_id", nullable = false)
    private Long kioskLocationId;

    @Column(name = "original_sale_id", nullable = false)
    private Long originalSaleId;

    @Column(name = "original_sale_item_id", nullable = false)
    private Long originalSaleItemId;

    @Column(name = "returned_product_id", nullable = false)
    private Long returnedProductId;

    @Column(name = "returned_color_id")
    private Long returnedColorId;

    @Column(name = "returned_size", length = 20)
    private String returnedSize;

    @Column(name = "returned_quantity", precision = 12, scale = 3, nullable = false)
    private BigDecimal returnedQuantity;

    @Column(name = "returned_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal returnedAmount;

    @Column(name = "given_product_id")
    private Long givenProductId;

    @Column(name = "given_color_id")
    private Long givenColorId;

    @Column(name = "given_size", length = 20)
    private String givenSize;

    @Column(name = "given_quantity", precision = 12, scale = 3)
    private BigDecimal givenQuantity;

    @Column(name = "given_amount", precision = 12, scale = 2)
    private BigDecimal givenAmount;

    @Column(name = "difference_amount", precision = 12, scale = 2)
    private BigDecimal differenceAmount;

    @Column(name = "new_sale_id")
    private Long newSaleId;

    @Column(name = "return_movement_id")
    private Long returnMovementId;

    @Column(name = "given_movement_id")
    private Long givenMovementId;

    @Column(name = "reintegro_movement_id")
    private Long reintegroMovementId;

    @Column(name = "authorized_by")
    private Long authorizedBy;

    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "apto")
    private Boolean apto;

    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "observations", length = 1500)
    private String observations;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "reintegrated_at")
    private LocalDateTime reintegratedAt;

    @Column(name = "reintegrated_by")
    private Long reintegratedBy;

    /** Conteo físico al que se asocia la devolución para cuadrar Salidas del periodo. */
    @Column(name = "physical_count_id")
    private Long physicalCountId;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
        if (status == null || status.isBlank()) {
            status = "DRAFT";
        }
        if (slipType == null || slipType.isBlank()) {
            slipType = "EXCHANGE";
        }
    }
}
