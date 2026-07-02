package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(
        name = "kiosk_promotion_tier",
        uniqueConstraints = @UniqueConstraint(columnNames = {"promotion_id", "audience_category", "category_id"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPromotionTierEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    private KioskPromotionEntity promotion;

    @Column(name = "audience_category", length = 20, nullable = false)
    private String audienceCategory;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "discount_value", precision = 12, scale = 2, nullable = false)
    private BigDecimal discountValue;
}
