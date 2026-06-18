package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "internal_shipment_request_line")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private InternalShipmentRequestEntity request;

    @Column(name = "line_order", nullable = false)
    private Integer lineOrder;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "size", length = 50)
    private String size;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;
}
