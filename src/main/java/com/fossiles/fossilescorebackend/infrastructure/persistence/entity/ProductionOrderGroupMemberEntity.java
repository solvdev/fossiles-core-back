package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Una OP dentro de una OP consolidada activa. Una OP está a lo más en una. */
@Entity
@Table(name = "production_order_group_member")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderGroupMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "production_order_id", nullable = false, unique = true)
    private Long productionOrderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
