package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_item_material_pick",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_item_material_pick", columnNames = {"task_item_id", "material_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskItemMaterialPickEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_item_id", nullable = false)
    private Long taskItemId;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean picked = false;

    @Column(name = "picked_at")
    private LocalDateTime pickedAt;

    @Column(name = "picked_by")
    private Long pickedBy;
}
