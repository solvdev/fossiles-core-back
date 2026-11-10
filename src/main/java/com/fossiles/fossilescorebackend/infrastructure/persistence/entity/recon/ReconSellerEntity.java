package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Catálogo de vendedores: el nombre es idéntico a online_sale.salesperson. */
@Entity
@Table(name = "recon_seller")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconSellerEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "name", nullable = false, length = 100)
    private String name;
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
