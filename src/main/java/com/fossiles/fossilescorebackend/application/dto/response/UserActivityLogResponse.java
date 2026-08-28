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
public class UserActivityLogResponse {
    private Long id;
    private Long userId;
    private String username;
    private String actionType;
    private String description;
    private String httpMethod;
    private String requestPath;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;
}
