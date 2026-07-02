package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "online_sale_return")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineSaleReturnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "online_sale_id", nullable = false)
    private Long onlineSaleId;

    @Column(name = "related_shipment_number", length = 50)
    private String relatedShipmentNumber;

    @Column(name = "return_reason", length = 500)
    private String returnReason;

    /** BUENO, DAÑADO, USADO */
    @Column(name = "item_condition", length = 30)
    private String itemCondition;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = GuatemalaDateTime.now();
        if (itemCondition == null || itemCondition.isBlank()) itemCondition = "BUENO";
    }
}

