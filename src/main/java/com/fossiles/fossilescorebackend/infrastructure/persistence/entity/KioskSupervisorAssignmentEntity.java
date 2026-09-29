package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "kiosk_supervisor_assignment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskSupervisorAssignmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supervisor_user_id", nullable = false)
    private Long supervisorUserId;

    @Column(name = "kiosk_location_id", nullable = false)
    private Long kioskLocationId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
    }
}
