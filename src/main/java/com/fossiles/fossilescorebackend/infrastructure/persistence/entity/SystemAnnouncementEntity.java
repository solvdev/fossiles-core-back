package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "system_announcement")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemAnnouncementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "announcement_type", nullable = false, length = 30)
    @Builder.Default
    private String announcementType = "RESTART_WARNING";

    @Column(name = "target_action", length = 50)
    @Builder.Default
    private String targetAction = "RESTART";

    @Column(name = "duration_seconds", nullable = false)
    @Builder.Default
    private Integer durationSeconds = 300;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserEntity createdByUser;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dismissed_by_user_id")
    private UserEntity dismissedByUser;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
        if (this.announcementType == null) {
            this.announcementType = "RESTART_WARNING";
        }
        if (this.targetAction == null) {
            this.targetAction = "RESTART";
        }
        if (this.durationSeconds == null) {
            this.durationSeconds = 300;
        }
    }
}
