package com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Historial de acciones, de solo agregar (trigger en la base). */
@Entity
@Table(name = "recon_event")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_id")
    private Long caseId;
    @Column(name = "batch_id")
    private Long batchId;
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;
    @Column(name = "from_status", length = 30)
    private String fromStatus;
    @Column(name = "to_status", length = 30)
    private String toStatus;
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;
    @Column(name = "actor_id")
    private Long actorId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
