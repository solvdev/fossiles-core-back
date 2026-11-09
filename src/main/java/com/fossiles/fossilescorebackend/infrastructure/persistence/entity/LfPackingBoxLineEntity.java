package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Piezas de una caja por línea de la orden y talla ({@code sizeKey} vacío cuando la línea no tiene tallas). */
@Entity
@Table(
        name = "lf_packing_box_line",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_lf_packing_box_line",
                columnNames = {"box_id", "production_order_item_id", "size_key"}),
        indexes = @Index(name = "idx_lf_packing_box_line_item", columnList = "production_order_item_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LfPackingBoxLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "box_id", nullable = false)
    private Long boxId;

    @Column(name = "production_order_item_id", nullable = false)
    private Long productionOrderItemId;

    @Column(name = "size_key", nullable = false, length = 40)
    @Builder.Default
    private String sizeKey = "";

    @Column(name = "quantity", nullable = false)
    private Integer quantity;
}
