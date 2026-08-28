package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "internal_shipment_request_slip")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestSlipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slip_number", length = 50, nullable = false, unique = true)
    private String slipNumber;

    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private String status = "PRINTED";

    @Column(name = "printed_at", nullable = false)
    private LocalDateTime printedAt;

    @Column(name = "printed_by")
    private Long printedBy;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (printedAt == null) {
            printedAt = now;
        }
        if (status == null || status.isBlank()) {
            status = "PRINTED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
