package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemAnnouncementResponse {

    private Long id;
    private String title;
    private String message;
    private String announcementType;
    private String targetAction;
    private Integer durationSeconds;
    private Long remainingSeconds;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private Boolean isActive;
    private String createdByUsername;
    private String createdByName;

    /** Instantes absolutos (epoch ms) para que el front cuente sin depender de zona ni de pollings. */
    private Long expiresAtEpochMs;
    private Long serverNowEpochMs;
    /** Identifica el arranque actual del back; cambia en cada reinicio. */
    private Long bootId;
}
