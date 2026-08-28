package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectedUserResponse {
    private Long id;
    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String profileImageUrl;
    private String status;
    private Set<String> roles;
    private String departmentName;
    private LocalDateTime lastActivityAt;
    private boolean isOnline;
    private Long minutesSinceLastActivity;
    private UserActivityLogResponse lastAction;
}
