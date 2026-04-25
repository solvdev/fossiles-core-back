package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "production_order_item_id")
    private Long productionOrderItemId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_code", length = 30)
    private String productCode;

    @Column(name = "product_name", length = 150)
    private String productName;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "color_name", length = 100)
    private String colorName;

    private Integer quantity;

    @Column(name = "estimated_hours")
    private Double estimatedHours;

    @Column(length = 500)
    private String observations;

    @Column(name = "materials_delivered")
    private Boolean materialsDelivered;

    @Column(name = "materials_delivered_at")
    private LocalDateTime materialsDeliveredAt;

    @Column(name = "leather_delivered")
    private Boolean leatherDelivered;

    @Column(name = "leather_delivered_at")
    private LocalDateTime leatherDeliveredAt;

    @Column(name = "day_sale_extra", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean daySaleExtra = false;
}

