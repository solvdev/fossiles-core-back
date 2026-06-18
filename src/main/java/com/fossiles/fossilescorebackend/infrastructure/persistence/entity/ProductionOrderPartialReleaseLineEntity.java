package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "production_order_partial_release_line")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderPartialReleaseLineEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "production_order_item_id", nullable = false)
    private Long productionOrderItemId;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "sizes_data", columnDefinition = "TEXT")
    private String sizesData;
}
