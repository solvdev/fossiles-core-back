package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "production_order_id")
    private Long productionOrderId;

    @Column(name = "production_order_code", length = 30)
    private String productionOrderCode;

    @Column(name = "production_order_item_id")
    private Long productionOrderItemId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", length = 150)
    private String productName;

    @Column(name = "product_code", length = 30)
    private String productCode;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "color_name", length = 100)
    private String colorName;

    private Integer quantity;

    @Column(name = "observations", length = 500)
    private String observations;

    private Integer desk;

    /**
     * Historial: mesa donde realmente se trabajo la tarea.
     * Se llena al completar la tarea y se conserva aunque desk sea limpiado.
     */
    @Column(name = "worked_desk")
    private Integer workedDesk;

    @Column(name = "estimated_hours")
    private Double estimatedHours;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    private Integer priority;

    @Column(name = "start_time", length = 5)
    private String startTime; // "HH:mm"

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "actual_duration_minutes")
    private Integer actualDurationMinutes;

    @Column(name = "waste_quantity")
    @Builder.Default
    private Integer wasteQuantity = 0;

    @Column(name = "waste_notes", columnDefinition = "TEXT")
    private String wasteNotes;

    @Column(name = "die_cut_ready", nullable = false)
    @Builder.Default
    private Boolean dieCutReady = false;

    @Column(name = "die_cut_date")
    private LocalDate dieCutDate;

    @Column(name = "leather_delivered", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean leatherDelivered = false;

    @Column(name = "leather_delivered_at")
    private LocalDateTime leatherDeliveredAt;

    @Column(name = "materials_delivered", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean materialsDelivered = false;

    @Column(name = "materials_delivered_at")
    private LocalDateTime materialsDeliveredAt;

    @Column(length = 20)
    private String status; // PENDING, IN_PROGRESS, COMPLETED, CANCELLED

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
        if (leatherDelivered == null) leatherDelivered = false;
        if (materialsDelivered == null) materialsDelivered = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
