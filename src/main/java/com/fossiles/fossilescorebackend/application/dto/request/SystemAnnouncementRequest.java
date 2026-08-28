package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemAnnouncementRequest {

    @NotBlank(message = "El título es obligatorio")
    private String title;

    @NotBlank(message = "El mensaje es obligatorio")
    private String message;

    @Builder.Default
    private String announcementType = "RESTART_WARNING"; // RESTART_WARNING, MAINTENANCE, INFO

    @Builder.Default
    private String targetAction = "RESTART"; // RESTART, LOGOUT, NONE

    /**
     * Duración en segundos (por defecto 300 = 5 minutos)
     */
    private Integer durationSeconds;

    /**
     * Duración en minutos (opcional como alternativa amigable)
     */
    private Integer durationMinutes;
}
