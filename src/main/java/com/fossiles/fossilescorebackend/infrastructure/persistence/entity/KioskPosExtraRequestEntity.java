package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Solicitud de complemento extra del POS de kioscos (monto por pieza) pendiente de autorización. */
@Entity
@Table(name = "kiosk_pos_extra_request")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosExtraRequestEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kiosk_location_id", nullable = false)
    private Long kioskLocationId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_code", length = 60)
    private String productCode;

    @Column(name = "product_name", length = 180)
    private String productName;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "color_name", length = 120)
    private String colorName;

    @Column(name = "size", length = 20)
    private String size;

    @Column(name = "hardware_condition", length = 40)
    private String hardwareCondition;

    /** Piezas que llevan el complemento. */
    @Column(name = "quantity", precision = 12, scale = 3, nullable = false)
    private BigDecimal quantity;

    /** Monto extra por pieza. */
    @Column(name = "extra_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal extraAmount;

    /** Precio que el cajero veía al pedir (informativo; el cobro recalcula en el servidor). */
    @Column(name = "catalog_unit_price", precision = 12, scale = 2)
    private BigDecimal catalogUnitPrice;

    @Column(name = "description", length = 300, nullable = false)
    private String description;

    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "request_date", nullable = false)
    private LocalDate requestDate;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "authorized_by")
    private Long authorizedBy;

    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "sale_id")
    private Long saleId;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = GuatemalaDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (requestDate == null) {
            requestDate = GuatemalaDateTime.today();
        }
        if (status == null || status.isBlank()) {
            status = STATUS_PENDING;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
